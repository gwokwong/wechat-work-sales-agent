package com.example.wechatsales.channel;

import com.example.wechatsales.config.AppProperties;
import com.example.wechatsales.crypto.WXBizMsgCrypt;
import com.example.wechatsales.domain.Message;
import com.example.wechatsales.repository.MessageLogRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;

/**
 * WeComChannel：真实企业微信通道（M1 实现）。
 *
 * <ul>
 *   <li>外发：{@link #send(OutboundMessage)} 调用 {@link WeComApiClient#sendTextMessage}
 *       （/cgi-bin/message/send 应用消息，touser 按 external_to_userid 映射解析）返回真实 msgid；</li>
 *   <li>回调：{@link #verifyUrl}/{@link #receiveCallback} 供 {@code WeComCallbackController}
 *       调用，验签 + 解密 + XML 解析后，以 msgId 幂等去重并异步投递 {@link MessageBus}；
 *       ack 立即返回（5 秒硬约束），丢消息由会话存档拉取兜底；</li>
 *   <li>configStatus：启动/管理端展示配置是否真实填写。</li>
 * </ul>
 *
 * <p>仅在 {@code app.wecom.enabled=true} 时创建（默认 false：M0/Mock 链路不受影响，
 * 未填真实配置时启动不报错，send 返回带原因的失败）。</p>
 */
@Slf4j
@Component("wecomChannel")
@ConditionalOnProperty(prefix = "app.wecom", name = "enabled", havingValue = "true")
public class WeComChannel implements Channel {

    private final AppProperties appProperties;
    private final WeComApiClient weComApiClient;
    private final MessageBus messageBus;
    private final MessageLogRepository messageLogRepository;
    private final TaskExecutor wecomCallbackExecutor;

    private WXBizMsgCrypt wxCrypt;

    public WeComChannel(AppProperties appProperties,
                        WeComApiClient weComApiClient,
                        MessageBus messageBus,
                        MessageLogRepository messageLogRepository,
                        TaskExecutor wecomCallbackExecutor) {
        this.appProperties = appProperties;
        this.weComApiClient = weComApiClient;
        this.messageBus = messageBus;
        this.messageLogRepository = messageLogRepository;
        this.wecomCallbackExecutor = wecomCallbackExecutor;
    }

    @PostConstruct
    public void init() {
        AppProperties.Wecom w = appProperties.getWecom();
        if (real(w.getCallbackToken()) && real(w.getCallbackAesKey()) && real(w.getCorpId())) {
            try {
                this.wxCrypt = new WXBizMsgCrypt(w.getCallbackToken(), w.getCallbackAesKey(), w.getCorpId());
            } catch (Exception e) {
                log.warn("[WeComChannel] 回调加解密初始化失败，回调将不可用: {}", e.getMessage());
            }
        } else {
            log.warn("[WeComChannel] 回调 Token/EncodingAESKey/corpId 未配置完整，回调入口将返回失败提示");
        }
        log.info("[WeComChannel] 真实企微通道已启用。corpId={} 配置完整={}",
                w.getCorpId(), weComApiClient.isConfigured());
    }

    @Override
    public String channelType() {
        return "wecom";
    }

    @Override
    public SendResult send(OutboundMessage message) {
        if (!weComApiClient.isConfigured()) {
            return SendResult.fail("企微凭据未配置完整（app.wecom.corp-id/session-secret/app-secret），"
                    + "请按 README M1 配置 application-wecom.yml 并 enabled=true");
        }
        return weComApiClient.sendTextMessage(message.contactExternalId(), message.content());
    }

    /** GET URL 验证：验签并解密 echostr，返回明文（失败抛异常由 Controller 转 4xx/5xx） */
    public String verifyUrl(String msgSignature, String timestamp, String nonce, String echoStr) {
        WXBizMsgCrypt crypt = requireCrypt();
        return crypt.verifyUrl(msgSignature, timestamp, nonce, echoStr);
    }

    /**
     * 回调 POST 处理：验签 → 解密 → 解析外部消息 → 幂等去重 → 异步投 MessageBus。
     * 必须快速返回 "success"（企微要求 5 秒内 ack）。
     *
     * @param encryptMsg 请求体 {@code <Encrypt>} 节点内容（密文）
     */
    public String receiveCallback(String msgSignature, String timestamp, String nonce, String encryptMsg) {
        WXBizMsgCrypt crypt = requireCrypt();
        // 验签失败直接抛异常（Controller 转 401），验签通过则解密
        String xml = crypt.decryptMsg(msgSignature, timestamp, nonce, encryptMsg);
        Optional<Message> message = WeComCallbackXml.parseInbound(xml);
        if (message.isEmpty()) {
            log.debug("[WeComChannel] 回调消息无需处理（事件/非文本/缺字段），ack 返回 success");
            return "success";
        }
        Message msg = message.get();
        dispatchAsync(msg);
        return "success";
    }

    /** 异步投递：以 msgId 幂等去重后 publish MessageBus */
    private void dispatchAsync(Message msg) {
        try {
            wecomCallbackExecutor.execute(() -> {
                try {
                    if (messageLogRepository.existsByMsgId(msg.getMsgId())) {
                        log.info("[WeComChannel] 回调消息已处理过，跳过 msgId={}", msg.getMsgId());
                        return;
                    }
                    messageBus.publish(msg);
                } catch (Exception e) {
                    log.error("[WeComChannel] 异步投递 MessageBus 失败 msgId={} err={}",
                            msg.getMsgId(), e.getMessage(), e);
                }
            });
        } catch (RejectedExecutionException e) {
            // 队列满不阻塞 ack；丢消息由会话存档定时拉取兜底补齐
            log.error("[WeComChannel] 回调任务队列已满，msgId={} 本次丢弃，将由会话存档拉取兜底",
                    msg.getMsgId());
        }
    }

    /** 返回当前配置是否已填写真实企微凭据（供启动/管理端校验与优雅降级） */
    public Map<String, Boolean> configStatus() {
        AppProperties.Wecom w = appProperties.getWecom();
        Map<String, Boolean> status = new HashMap<>();
        status.put("enabled", w.isEnabled());
        status.put("corpId", real(w.getCorpId()));
        status.put("sessionSecret", real(w.getSessionSecret()));
        status.put("callbackToken", real(w.getCallbackToken()));
        status.put("callbackAesKey", real(w.getCallbackAesKey()));
        status.put("sessionArchivePrivateKey", real(w.getSessionArchivePrivateKey()));
        status.put("agentId", real(w.getAgentId()));
        status.put("appSecret", real(w.getAppSecret()));
        status.put("archivePullEnabled", w.isArchivePullEnabled());
        return status;
    }

    /** 供管理端展示当前企业成员/外部前缀等方向识别配置 */
    public List<String> externalPrefixes() {
        return appProperties.getWecom().getExternalIdPrefixes();
    }

    private WXBizMsgCrypt requireCrypt() {
        if (wxCrypt == null) {
            throw new IllegalStateException("回调加解密未初始化：请配置 app.wecom.callback-token / "
                    + "callback-aes-key / corp-id（真实值）后重启");
        }
        return wxCrypt;
    }

    private boolean real(String s) {
        return s != null && !s.isBlank() && !s.startsWith("YOUR_");
    }
}

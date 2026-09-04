package com.example.wechatsales.channel;

import com.example.wechatsales.config.AppProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * WeComChannel：真实企业微信通道实现（占位）。
 *
 * <p>M1 阶段接入清单（详见 DESIGN.md / README.md）：</p>
 * <ol>
 *   <li>配置 app.wecom.corp-id / session-secret / agent-id / app-secret；</li>
 *   <li>在企微管理后台配置"会话内容存档"回调 URL，本类提供
 *       {@code receiveCallback(...)} 供 Controller 调用；</li>
 *   <li>回调消息验签（Token + EncodingAESKey）、解密（RSA 私钥）、
 *       按 msgId 幂等后 publish 到 {@link MessageBus}；</li>
 *   <li>外发走企微"客户联系"发送应用消息接口，替换下方 {@link #send(OutboundMessage)} 内 TODO。</li>
 * </ol>
 *
 * <p>当前为占位实现：不发起任何真实网络请求，避免误用造成资损。
 * 尝试在未接入真实配置时使用会抛出带说明的异常。</p>
 */
@Slf4j
@Component("wecomChannel")
@RequiredArgsConstructor
public class WeComChannel implements Channel {

    private final AppProperties appProperties;

    @PostConstruct
    public void init() {
        log.info("[WeComChannel] 已注册为真实企微通道（占位）。active=mock 时不会生效。");
    }

    @Override
    public String channelType() {
        return "wecom";
    }

    @Override
    public SendResult send(OutboundMessage message) {
        // TODO(M1): 调用企业微信「客户联系-发送应用消息」接口
        //   1. 用 app.wecom.app-secret 换取 access_token；
        //   2. POST https://qyapi.weixin.qq.com/cgi-bin/message/send
        //      {touser: contactExternalId, msgtype: "text", agentid: agentId, text:{content}}
        //   3. 使用返回的 msgid 作为 SendResult.messageId 用于审计；
        //   4. 注意企微外发频率限制与超时重试策略。
        throw new IllegalStateException(
                "WeComChannel 尚未接入真实企微。请完成 M1 配置（corpId/secret/会话存档私钥等）并切换 app.channel.active=wecom。详见 README.md M1 步骤清单。");
    }

    /**
     * 企微会话存档/客户消息回调入口（占位，M1 由 Web Controller 暴露）。
     * 真实实现要点：
     * <pre>
     * 1. 验签：校验 msg_signature / timestamp / nonce（使用 callback-token）；
     * 2. 解密：对 echostr 或消息体使用 callback-aes-key AES-CBC 解密；
     * 3. 同步返回 ack 字符串（成功必须尽快响应，企微要求 5 秒内）；
     * 4. 解密后的 XML 中提取 FromUserName(=external_userid)、MsgId、Content 等，
     *    以 MsgId 幂等去重后 publish 到 MessageBus，由编排器异步处理；
     * 5. 会话存档（会话内容存档-secret + RSA 私钥解密 media_data）请另设定时拉取任务。
     * </pre>
     *
     * @return 5 秒内必须返回的 ack（真实接入时返回 "success"）
     */
    public String receiveCallback(Map<String, String> params, String rawBody) {
        // TODO(M1): 验签 + 解密 + 投递 MessageBus
        log.warn("[WeComChannel] 收到企微回调（占位，未真正处理）: params={}", params);
        return "success";
    }

    /** 返回当前配置是否已填写真实企微凭据（供启动时校验） */
    public Map<String, Boolean> configStatus() {
        AppProperties.Wecom w = appProperties.getWecom();
        Map<String, Boolean> status = new HashMap<>();
        status.put("corpId", !isBlank(w.getCorpId()) && !"YOUR_CORP_ID".equals(w.getCorpId()));
        status.put("sessionSecret", !isBlank(w.getSessionSecret()) && !"YOUR_SESSION_ARCHIVE_SECRET".equals(w.getSessionSecret()));
        status.put("callbackToken", !isBlank(w.getCallbackToken()) && !"YOUR_CALLBACK_TOKEN".equals(w.getCallbackToken()));
        status.put("callbackAesKey", !isBlank(w.getCallbackAesKey()) && !"YOUR_CALLBACK_AES_KEY".equals(w.getCallbackAesKey()));
        status.put("sessionArchivePrivateKey", !isBlank(w.getSessionArchivePrivateKey()) && !"YOUR_PRIVATE_KEY_PEM".equals(w.getSessionArchivePrivateKey()));
        return status;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

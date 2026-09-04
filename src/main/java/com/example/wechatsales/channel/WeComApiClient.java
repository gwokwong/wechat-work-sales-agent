package com.example.wechatsales.channel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 企业微信官方 API 客户端（真实接入占位实现）。
 *
 * <p>真实接入会话存档需要企业认证与以下配置（TODO M1）：
 * <ul>
 *   <li>corpId / secret（会话存档 secret）→ {@code wecom.corp-id} / {@code wecom.secret}</li>
 *   <li>会话存档私钥：企微用您的 RSA 公钥加密会话密钥，本服务用私钥解密（建议走官方
 *       WeWorkFinanceSdk C++ SDK proxy，或 KMS 保管私钥，勿硬编码/入 git）</li>
 *   <li>回调 Token / EncodingAESKey（消息与事件回调验签解密）</li>
 * </ul></p>
 *
 * <p>对接步骤（实现 TODO 替换为真实 HTTP/SDK 调用）：
 * <ol>
 *   <li>getaccess_token：用 secret 换 access_token，缓存 2 小时并自动续期；</li>
 *   <li>getchatdata：会话存档按 seq 增量拉取（seq 游标需持久化），拉回的是加密 msg；</li>
 *   <li>解密：官方 SDK/自研 AES-GCM 解密后映射为 {@link Message}（msgId / externalUserId /
 *       content / msgType）投递给 {@link MessageBus}；</li>
 *   <li>sendtext：客户联系-发送应用消息（注意企微外部联系人 48 小时主动消息窗口限制）；</li>
 *   <li>回调：另建回调 RestController 验签后快速 ack，异步投递 MessageBus（5 秒 ack 要求）。</li>
 * </ol></p>
 *
 * <p>官方文档与接口路径见 TODO：getaccess_token / getchatdata / sendtext 均以常量注释形式
 * 占位，避免散落各处的魔法字符串。</p>
 */
@Slf4j
@Component
public class WeComApiClient {

    // ---- 官方接口路径（TODO M1 替换为真实 HTTP 客户端）----
    public static final String URL_GET_TOKEN = "https://qyapi.weixin.qq.com/cgi-bin/gettoken";
    public static final String URL_GET_CHAT_DATA = "https://qyapi.weixin.qq.com/cgi-bin/msgaudit/getchatdata";
    public static final String URL_SEND_MSG = "https://qyapi.weixin.qq.com/cgi-bin/message/send";

    // ---- 配置注入点（配置项在 application-*.yml 中 wecom.* 注释说明，由 M1 接线）----
    private String corpId;
    private String secret;
    private String archivePrivateKeyPath; // TODO 会话存档私钥路径（建议 KMS / 环境变量）
    private String callbackToken;
    private String callbackEncodingAesKey;

    /** 换取 access_token（TODO M1：真实实现 + 2 小时缓存自动续期） */
    public String getAccessToken() {
        log.warn("[WeComApiClient] 占位实现未调用真实企微 API；请按 README M1 清单接入 corpId/secret 后实现");
        throw new UnsupportedOperationException("真实企微接入尚未实现（M1 TODO）");
    }

    /** 增量拉取会话存档（TODO M1：实现 seq 游标持久化 + 官方 SDK 解密） */
    public String pullChatData(long seq) {
        throw new UnsupportedOperationException("真实企微接入尚未实现（M1 TODO）");
    }

    /** 发送文本消息（TODO M1：调用客户联系/应用消息发送接口，含频率限制） */
    public void sendTextMessage(String externalUserId, String content) {
        throw new UnsupportedOperationException("真实企微接入尚未实现（M1 TODO）");
    }
}

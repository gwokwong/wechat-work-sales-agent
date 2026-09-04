package com.example.wechatsales.channel;

import com.example.wechatsales.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 企业微信官方 API 客户端（M1 真实实现）。
 *
 * <p>接口文档：
 * <ul>
 *   <li>获取 access_token：GET /cgi-bin/gettoken?corpid=&amp;corpsecret=（有效期 7200s，
 *       每日限额 200 万次，本类内存缓存并提前 5 分钟自动续期）；</li>
 *   <li>拉取会话存档：POST /cgi-bin/msgaudit/getchatdata（seq 增量，limit 默认 1000）；</li>
 *   <li>发送应用消息：POST /cgi-bin/message/send。</li>
 * </ul>
 * HTTP 使用 Spring Boot 3.2 自带 {@link RestClient}，超时 5s/15s；响应用 Jackson 解析。</p>
 */
@Slf4j
@Component
public class WeComApiClient {

    public static final String URL_GET_TOKEN = "https://qyapi.weixin.qq.com/cgi-bin/gettoken";
    public static final String URL_GET_CHAT_DATA = "https://qyapi.weixin.qq.com/cgi-bin/msgaudit/getchatdata";
    public static final String URL_SEND_MSG = "https://qyapi.weixin.qq.com/cgi-bin/message/send";

    /** 常见错误码降级说明（60011/45009/48002 等） */
    public static String describeErrCode(int errcode) {
        return switch (errcode) {
            case 0 -> "ok";
            case 60011 -> "无管理/调用权限：请检查应用是否获得「会话内容存档/客户联系」权限、成员是否在可见范围或已授权";
            case 45009 -> "接口调用超过频率限制：请按企微频率限额退避重试（或改用批量接口）";
            case 48002 -> "API 不可用：该接口未对当前应用开放/未授权，请在管理后台开通";
            case 301020 -> "客户未同意会话存档：外部联系人需本人同意后才返回内容";
            default -> "企业微信错误码 " + errcode;
        };
    }

    private final AppProperties.Wecom wecom;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReentrantLock tokenLock = new ReentrantLock();

    private volatile TokenCache sessionToken;
    private volatile TokenCache appToken;

    public WeComApiClient(AppProperties appProperties) {
        this.wecom = appProperties.getWecom();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /** 是否已填写真实企微凭据（占位值视为未配置） */
    public boolean isConfigured() {
        AppProperties.Wecom w = wecom;
        return real(w.getCorpId()) && real(w.getSessionSecret()) && real(w.getAppSecret());
    }

    // ==================== access_token ====================

    /** 获取「会话存档」接口 access_token（sessionSecret，带缓存自动续期） */
    public String sessionAccessToken() {
        if (!real(wecom.getSessionSecret())) {
            throw new IllegalStateException("会话存档 secret 未配置（app.wecom.session-secret）");
        }
        return resolveToken("session", wecom.getSessionSecret(), sessionToken, t -> sessionToken = t);
    }

    /** 获取「应用消息发送」接口 access_token（appSecret，带缓存自动续期） */
    public String appAccessToken() {
        if (!real(wecom.getAppSecret())) {
            throw new IllegalStateException("应用 app-secret 未配置（app.wecom.app-secret）");
        }
        return resolveToken("app", wecom.getAppSecret(), appToken, t -> appToken = t);
    }

    private interface TokenSetter {
        void set(TokenCache t);
    }

    private String resolveToken(String scope, String secret, TokenCache cache, TokenSetter setter) {
        long now = System.currentTimeMillis() / 1000;
        if (cache != null && cache.expireAtSec() - now > 300) { // 过期前 5 分钟自动续期
            return cache.token();
        }
        tokenLock.lock();
        try {
            // 双检锁：等锁期间可能已被其他线程刷新
            TokenCache latest = scope.equals("session") ? sessionToken : appToken;
            if (latest != null && latest.expireAtSec() - System.currentTimeMillis() / 1000 > 300) {
                return latest.token();
            }
            JsonNode json = restClient.get()
                    .uri(URL_GET_TOKEN + "?corpid={corpid}&corpsecret={secret}", wecom.getCorpId(), secret)
                    .retrieve().body(JsonNode.class);
            int errcode = json.path("errcode").asInt(-1);
            if (errcode != 0) {
                throw new IllegalStateException("获取 access_token 失败 errcode=" + errcode
                        + " errmsg=" + json.path("errmsg").asText() + " → " + describeErrCode(errcode));
            }
            String token = json.path("access_token").asText();
            int expiresIn = json.path("expires_in").asInt(7200);
            TokenCache cached = new TokenCache(token, System.currentTimeMillis() / 1000 + expiresIn);
            setter.set(cached);
            log.info("[WeComApiClient] {} access_token 刷新成功，有效期 {}s", scope, expiresIn);
            return token;
        } catch (Exception e) {
            if (e instanceof IllegalStateException ise && ise.getMessage() != null
                    && ise.getMessage().contains("access_token")) {
                throw ise;
            }
            throw new IllegalStateException("请求企微 gettoken 失败: " + e.getMessage(), e);
        } finally {
            tokenLock.unlock();
        }
    }

    // ==================== 会话存档拉取 ====================

    /**
     * 按 seq 增量拉取会话存档。
     *
     * @return 拉取结果（nextSeq 为下次起点；异常时抛出，由调用方决定不推进 seq）
     */
    public ChatDataResult pullChatData(long seq) {
        Map<String, Object> body = new HashMap<>();
        body.put("seq", seq);
        body.put("limit", wecom.getArchivePullLimit());
        JsonNode json;
        try {
            json = restClient.post()
                    .uri(URL_GET_CHAT_DATA + "?access_token={token}", sessionAccessToken())
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            throw new IllegalStateException("请求企微 getchatdata 失败 seq=" + seq + ": " + e.getMessage(), e);
        }
        int errcode = json.path("errcode").asInt(-1);
        if (errcode != 0) {
            throw new IllegalStateException("getchatdata 拉取失败 seq=" + seq + " errcode=" + errcode
                    + " errmsg=" + json.path("errmsg").asText() + " → " + describeErrCode(errcode));
        }
        long nextSeq = json.path("next_seq").asLong(seq);
        List<ArchiveChatRecord> records = new ArrayList<>();
        JsonNode data = json.path("data");
        if (data != null && data.isArray()) {
            for (JsonNode n : data) {
                records.add(new ArchiveChatRecord(
                        n.path("seq").asLong(),
                        n.path("msgid").asText(),
                        n.path("publickey_ver").asInt(1),
                        n.path("encrypt_random_key").asText(),
                        n.path("encrypt_chat_msg").asText()));
            }
        }
        return new ChatDataResult(nextSeq, records);
    }

    public record ChatDataResult(long nextSeq, List<ArchiveChatRecord> records) {
    }

    // ==================== 发送文本（应用消息） ====================

    /**
     * 发送文本消息（/cgi-bin/message/send 应用消息）。
     *
     * <p>说明：企微 message/send 的 touser 要求企业成员 userid。给外部客户发消息需将
     * external_userid 映射到负责跟进该客户的员工 userid（微信客服 send_msg / 客户群发 /
     * 员工在企业微信客户端外发等不同场景）。本类按 {@code app.wecom.external-to-userid} 映射
     * 解析 touser；映射缺失时按原 external_userid 直发并 WARN（真实联调前务必配置映射或按
     * 企微合规采用其他外发通道，参见 README M1 注意事项）。</p>
     *
     * @param externalUserId 客户外部联系人 id（映射后可转为企微成员 userid）
     */
    public SendResult sendTextMessage(String externalUserId, String content) {
        String touser = wecom.resolveTouser(externalUserId);
        Map<String, Object> text = new HashMap<>();
        text.put("content", content);
        Map<String, Object> body = new HashMap<>();
        body.put("touser", touser);
        body.put("msgtype", "text");
        body.put("agentid", parseAgentId());
        body.put("text", text);
        try {
            JsonNode json = restClient.post()
                    .uri(URL_SEND_MSG + "?access_token={token}", appAccessToken())
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            int errcode = json.path("errcode").asInt(-1);
            String errmsg = json.path("errmsg").asText("");
            if (errcode == 0) {
                String msgId = json.path("msgid").asText(null);
                log.info("[WeComApiClient] 消息发送成功 msgid={} touser={}", msgId, touser);
                return SendResult.ok(msgId);
            }
            log.warn("[WeComApiClient] 消息发送失败 errcode={} errmsg={}（{}）",
                    errcode, errmsg, describeErrCode(errcode));
            return SendResult.fail("errcode=" + errcode + " errmsg=" + errmsg + " → " + describeErrCode(errcode));
        } catch (Exception e) {
            log.error("[WeComApiClient] 消息发送请求异常 touser={}: {}", touser, e.getMessage(), e);
            return SendResult.fail("请求企微 message/send 失败: " + e.getMessage());
        }
    }

    // ==================== helpers ====================

    private record TokenCache(String token, long expireAtSec) {
    }

    /** 企微接口要求 agentid 为数值，配置为字符串时此处解析 */
    private Long parseAgentId() {
        try {
            return Long.parseLong(wecom.getAgentId().trim());
        } catch (Exception e) {
            throw new IllegalStateException("app.wecom.agent-id 必须为数字，当前: " + wecom.getAgentId());
        }
    }

    private boolean real(String s) {
        return s != null && !s.isBlank() && !s.startsWith("YOUR_");
    }
}

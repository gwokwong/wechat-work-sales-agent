package com.example.wechatsales.strategy;

import com.example.wechatsales.config.AppProperties;
import com.example.wechatsales.context.ContactContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HttpLLMClient：LLMClient 的真实 HTTP 实现（OpenAI 兼容 chat/completions）。
 *
 * <p>当 {@code app.llm.mock=false} 时注册（MockLLMClient 按条件自动停用），
 * 使用 Spring {@link RestClient} 向 OpenAI 兼容服务（DeepSeek/混元/自建网关等）POST
 * /chat/completions，让大模型在【仅润色、不新增承诺/价格/折扣、保持原意、中文输出】约束下
 * 对模板话术做个性化润色。</p>
 *
 * <p>请求协议约定（OpenAI 兼容协议，绝大多数厂商可直连）：
 * <pre>
 * POST {base-url}/chat/completions
 * headers: Content-Type: application/json, Authorization: Bearer {app.llm.api-key}
 * body: {"model":"deepseek-chat","messages":[{role:system,...},{role:user,...}],"temperature":0.7}
 * 200:  {"choices":[{"message":{"role":"assistant","content":"润色后文本"}}]}
 * </pre>
 * 健壮性：任何异常（网络/HTTP 非 2xx/响应为空/JSON 解析失败）一律 WARN 后回退返回
 * 原始 rendered 文本，不向上抛异常，保证演示/生产不因 LLM 故障阻断链路；不重试。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.llm", name = "mock", havingValue = "false")
public class HttpLLMClient implements LLMClient {

    /** OpenAI 兼容补全接口路径（相对 base-url，绝大多数厂商为 /chat/completions） */
    public static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    static final String SYSTEM_PROMPT = "你是企业销售助手。请仅对给定话术做润色，使其更自然、口语化、贴合客户上下文；"
            + "不得新增任何承诺、价格或折扣信息，不得改变原意；一律用中文输出；"
            + "只返回润色后的文本本身，不要任何解释、前后缀或 markdown 标记。";

    private final AppProperties appProperties;
    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HttpLLMClient(RestClient.Builder restClientBuilder, AppProperties appProperties) {
        this.restClientBuilder = restClientBuilder;
        this.appProperties = appProperties;
    }

    @Override
    public String generate(ContactContext ctx, StrategyConfig strategy, String draft) {
        String fallback = draft == null ? "" : draft;
        AppProperties.Llm llm = appProperties.getLlm();
        if (llm.getBaseUrl() == null || llm.getBaseUrl().isBlank()
                || llm.getModel() == null || llm.getModel().isBlank()) {
            log.warn("[HttpLLMClient] app.llm.base-url/model 未配置，回退原话术");
            return fallback;
        }
        try {
            String content = callChatCompletions(llm, buildMessages(ctx, strategy, fallback));
            if (content == null || content.isBlank()) {
                log.warn("[HttpLLMClient] LLM 返回空响应，回退原话术");
                return fallback;
            }
            log.info("[HttpLLMClient] 润色成功 model={} 原长={} 润色后长={}",
                    llm.getModel(), fallback.length(), content.length());
            return content.trim();
        } catch (Exception e) {
            // 网络异常 / HTTP 非 2xx / JSON 解析失败等一律回退原文本，不阻断编排链路
            log.warn("[HttpLLMClient] 调用真实 LLM 失败，回退原话术: {}", e.getMessage());
            return fallback;
        }
    }

    /** 组装 OpenAI 兼容 messages：system 润色约束 + user 待润色话术与一行式客户上下文摘要 */
    JsonNode buildMessages(ContactContext ctx, StrategyConfig strategy, String draft) {
        ArrayNode messages = objectMapper.createArrayNode();

        ObjectNode system = objectMapper.createObjectNode();
        system.put("role", "system");
        system.put("content", SYSTEM_PROMPT);
        messages.add(system);

        ObjectNode user = objectMapper.createObjectNode();
        user.put("role", "user");
        user.put("content", "【待润色话术】\n" + draft
                + "\n【客户上下文】\n" + oneLineContext(ctx, strategy));
        messages.add(user);
        return messages;
    }

    /** 客户上下文压成一行摘要：客户名/商机阶段/画像摘要/最近客户消息（截断）/命中策略 */
    private String oneLineContext(ContactContext ctx, StrategyConfig strategy) {
        StringBuilder sb = new StringBuilder();
        if (ctx == null) {
            return "无";
        }
        if (ctx.customerName() != null) {
            sb.append("客户=").append(ctx.customerName());
        }
        if (ctx.getDeal() != null && ctx.getDeal().getStage() != null) {
            appendSep(sb).append("商机阶段=").append(ctx.getDeal().getStage());
        }
        if (ctx.getProfile() != null) {
            appendProfileSummary(sb, ctx);
        }
        if (ctx.getRecentCustomerMessages() != null && !ctx.getRecentCustomerMessages().isEmpty()) {
            appendSep(sb).append("最近客户消息=").append(summarizeRecent(ctx.getRecentCustomerMessages()));
        }
        if (strategy != null && strategy.getRuleName() != null) {
            appendSep(sb).append("命中策略=").append(strategy.getRuleName());
        }
        return sb.isEmpty() ? "无" : sb.toString();
    }

    private void appendProfileSummary(StringBuilder sb, ContactContext ctx) {
        StringBuilder p = new StringBuilder();
        appendIfNotBlank(p, "阶段摘要", ctx.getProfile().getStageSummary());
        appendIfNotBlank(p, "需求摘要", ctx.getProfile().getNeedsSummary());
        appendIfNotBlank(p, "偏好", ctx.getProfile().getPreferredTopics());
        appendIfNotBlank(p, "风险", ctx.getProfile().getRiskNotes());
        if (!p.isEmpty()) {
            appendSep(sb).append("画像(").append(p).append(")");
        }
    }

    private void appendIfNotBlank(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append("；");
            }
            sb.append(label).append("=").append(trimTo(value, 120));
        }
    }

    private String summarizeRecent(Iterable<String> messages) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String msg : messages) {
            if (msg == null || msg.isBlank()) {
                continue;
            }
            if (count >= 3) {
                break;
            }
            if (!sb.isEmpty()) {
                sb.append(" | ");
            }
            sb.append(trimTo(msg, 80));
            count++;
        }
        return sb.isEmpty() ? "无" : trimTo(sb.toString(), 300);
    }

    private StringBuilder appendSep(StringBuilder sb) {
        return sb.isEmpty() ? sb : sb.append("，");
    }

    private String trimTo(String s, int max) {
        String v = s == null ? "" : s.trim();
        return v.length() <= max ? v : v.substring(0, max) + "…";
    }

    /** 调用 OpenAI 兼容 /chat/completions 并取 choices[0].message.content */
    private String callChatCompletions(AppProperties.Llm llm, JsonNode messages) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", llm.getModel());
        body.put("messages", messages);
        body.put("temperature", llm.getTemperature());

        String responseBody = restClientBuilder.build().post()
                .uri(llm.getBaseUrl() + CHAT_COMPLETIONS_PATH)
                .header("Authorization", "Bearer " + (llm.getApiKey() == null ? "" : llm.getApiKey()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            return null;
        }
        return content.asText();
    }
}

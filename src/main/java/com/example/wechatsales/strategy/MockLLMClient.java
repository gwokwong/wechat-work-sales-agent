package com.example.wechatsales.strategy;

import com.example.wechatsales.context.ContactContext;
import com.example.wechatsales.domain.SalesStage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * MockLLMClient：不真实调用大模型，基于上下文做轻度规则个性化，
 * 保证演示链路无外部依赖即可跑通（话术质量、策略覆盖等指标会在接入真实 LLM 后提升）。
 *
 * <p>个性化规则：结合客户最近一条消息，追加一句回应或问询，模拟 LLM 的上下文感知。</p>
 *
 * <p>装配：app.llm.mock=true（默认）时注册；置 false 后由 {@link HttpLLMClient} 接管，
 * 二者由条件互斥，避免同一 LLMClient 接口出现多 bean 歧义。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.llm", name = "mock", havingValue = "true", matchIfMissing = true)
public class MockLLMClient implements LLMClient {

    @Override
    public String generate(ContactContext ctx, StrategyConfig strategy, String draft) {
        // 1) 基础话术直接来自模板渲染结果（已替换 {{customerName}} 等占位符）
        StringBuilder sb = new StringBuilder(draft == null ? "" : draft.trim());

        // 2) 追加 Mock 个性化尾巴：模拟“LLM 依据最近对话追加一句话”
        String lastMsg = lastCustomerMessage(ctx);
        if (lastMsg != null && !lastMsg.isBlank() && SalesStage.NEEDS_ANALYSIS == currentStageOf(ctx)) {
            sb.append("\n（附：刚看到您提到“").append(trimTo(lastMsg, 30))
              .append("”，我们在这方面有成熟案例，方便时可以细聊。）");
        } else if (lastMsg != null && !lastMsg.isBlank()
                && (lastMsg.contains("价格") || lastMsg.contains("预算") || lastMsg.contains("多少钱"))) {
            sb.append("\n（附：如方便告知大概的使用人数与期望模块，我可以帮您出一个更贴近的初步测算。）");
        }

        String result = sb.toString().trim();
        log.info("[MockLLMClient] ctx={} strategy={} 生成话术长度={}",
                ctx == null ? "-" : ctx.customerName(),
                strategy == null ? "-" : strategy.getRuleName(),
                result.length());
        return result;
    }

    private SalesStage currentStageOf(ContactContext ctx) {
        if (ctx == null || ctx.getDeal() == null || ctx.getDeal().getStage() == null) {
            return SalesStage.LEAD_INITIAL;
        }
        return ctx.getDeal().getStage();
    }

    private String lastCustomerMessage(ContactContext ctx) {
        if (ctx == null || ctx.getRecentCustomerMessages() == null || ctx.getRecentCustomerMessages().isEmpty()) {
            return null;
        }
        List<String> msgs = ctx.getRecentCustomerMessages();
        return msgs.get(msgs.size() - 1);
    }

    private String trimTo(String s, int max) {
        String v = s == null ? "" : s.trim();
        return v.length() <= max ? v : v.substring(0, max) + "…";
    }
}

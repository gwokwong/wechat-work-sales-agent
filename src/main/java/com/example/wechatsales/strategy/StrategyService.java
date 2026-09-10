package com.example.wechatsales.strategy;

import com.example.wechatsales.context.ContactContext;
import com.example.wechatsales.domain.SalesStage;
import com.example.wechatsales.repository.StrategyConfigRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 策略服务：策略装载（JSON → strategy_config）+ 按「阶段 + 触发条件」选策略 + 模板渲染。
 *
 * <p>策略按阶段配置：{@code stage} 定位阶段 → 同阶段内按 priority 升序，
 * 优先匹配 triggerKeywords 命中的规则，未命中时回落到该阶段「无触发词兜底规则」。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyService {

    public static final String ACTION_SEND_TEXT = "SEND_TEXT";
    public static final String ACTION_CREATE_QUOTE = "CREATE_QUOTE";
    public static final String ACTION_HUMAN_TRANSFER = "HUMAN_TRANSFER";

    private final StrategyConfigRepository strategyConfigRepository;
    private final ObjectMapper objectMapper;

    /** JSON 文件中的策略结构（triggerKeywords 为数组） */
    record StrategyJson(String stage, String ruleName, List<String> triggerKeywords,
                        String actionType, String templateContent, int priority, boolean enabled,
                        Integer minIntervalMinutes) {
    }

    /** 从 classpath JSON 装载策略配置（幂等：仅在表为空时由 DemoDataInitializer 调用） */
    @Transactional
    public int loadStrategiesFromClasspath(String classpathJson) {
        try {
            InputStream in = new ClassPathResource(classpathJson).getInputStream();
            List<StrategyJson> items = objectMapper.readValue(in, new TypeReference<List<StrategyJson>>() {
            });
            int count = 0;
            for (StrategyJson item : items) {
                StrategyConfig cfg = new StrategyConfig();
                cfg.setStage(item.stage());
                cfg.setRuleName(item.ruleName());
                cfg.setTriggerKeywords(item.triggerKeywords() == null || item.triggerKeywords().isEmpty()
                        ? null
                        : String.join("|", item.triggerKeywords()));
                cfg.setActionType(item.actionType() == null ? ACTION_SEND_TEXT : item.actionType());
                cfg.setTemplateContent(item.templateContent());
                cfg.setPriority(item.priority() == 0 ? 100 : item.priority());
                cfg.setEnabled(item.enabled());
                cfg.setMinIntervalMinutes(item.minIntervalMinutes() == null ? 0 : item.minIntervalMinutes());
                cfg.touch();
                strategyConfigRepository.save(cfg);
                count++;
            }
            log.info("已从 {} 装载 {} 条策略", classpathJson, count);
            return count;
        } catch (Exception e) {
            throw new IllegalStateException("装载策略 JSON 失败: " + classpathJson, e);
        }
    }

    /**
     * 按客户当前阶段与最新客户消息选择回复策略。
     * 返回 null 表示该阶段无可用策略（不自动回复，交给人工）。
     */
    public StrategyConfig selectForMessage(ContactContext ctx, String latestCustomerMessage) {
        SalesStage stage = ctx.getDeal() == null ? SalesStage.LEAD_INITIAL : ctx.getDeal().getStage();
        List<StrategyConfig> rules = strategyConfigRepository.findByStageAndEnabledTrueOrderByPriorityAsc(stage.name());
        if (rules.isEmpty()) {
            return null;
        }
        String msg = latestCustomerMessage == null ? "" : latestCustomerMessage.trim();
        // 第一遍：同阶段内找触发关键词命中（按 priority 升序，取最早命中）
        for (StrategyConfig rule : rules) {
            if (ruleMatchesMessage(rule, msg)) {
                return rule;
            }
        }
        // 第二遍：回落该阶段兜底规则（trigger_keywords 为空）
        for (StrategyConfig rule : rules) {
            if (rule.getTriggerKeywords() == null || rule.getTriggerKeywords().isBlank()) {
                return rule;
            }
        }
        return rules.get(0);
    }

    private boolean ruleMatchesMessage(StrategyConfig rule, String message) {
        String keywords = rule.getTriggerKeywords();
        if (keywords == null || keywords.isBlank()) {
            return false;
        }
        for (String kw : keywords.split("\\|")) {
            if (kw != null && !kw.isBlank() && message.contains(kw.trim())) {
                return true;
            }
        }
        return false;
    }

    /** 模板渲染：替换 {{customerName}} / {{quoteReference}} 占位符 */
    public String render(StrategyConfig rule, ContactContext ctx, String quoteReference) {
        String content = rule.getTemplateContent() == null ? "" : rule.getTemplateContent();
        String customerName = ctx.customerName();
        content = content.replace("{{customerName}}", customerName == null ? "客户" : customerName);
        if (quoteReference != null && !quoteReference.isBlank()) {
            content = content.replace("{{quoteReference}}", quoteReference);
        }
        return content.trim();
    }

    /** 全部启用策略（管理端查看/演示） */
    public List<StrategyConfig> listAll() {
        return strategyConfigRepository.findAll();
    }
}

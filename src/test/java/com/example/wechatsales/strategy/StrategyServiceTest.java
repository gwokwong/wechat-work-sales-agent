package com.example.wechatsales.strategy;

import com.example.wechatsales.context.ContactContext;
import com.example.wechatsales.domain.Contact;
import com.example.wechatsales.domain.Deal;
import com.example.wechatsales.domain.SalesStage;
import com.example.wechatsales.repository.StrategyConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

/**
 * 策略服务单测：关键词命中优先兜底、无策略返回 null、模板占位符渲染。
 */
@ExtendWith(MockitoExtension.class)
class StrategyServiceTest {

    @Mock
    private StrategyConfigRepository repository;

    private StrategyService service;

    @BeforeEach
    void setUp() {
        service = new StrategyService(repository, new ObjectMapper());
    }

    @Test
    void keywordRuleWinsOverFallback() {
        StrategyConfig fallback = config(100, null, "兜底-闲聊", "SEND_TEXT", "您好，很高兴为您服务，请问有什么可以帮您？");
        StrategyConfig priceHit = config(10, "报价|多少钱|怎么收费", "报价-价格", "CREATE_QUOTE", "已为您创建报价单 【报价单 {{quoteReference}}】");
        when(repository.findByStageAndEnabledTrueOrderByPriorityAsc("PROPOSAL"))
                .thenReturn(List.of(fallback, priceHit));

        ContactContext ctx = ContactContext.builder()
                .contact(contact())
                .deal(deal(SalesStage.PROPOSAL))
                .recentCustomerMessages(List.of("你们怎么收费？"))
                .build();

        StrategyConfig hit = service.selectForMessage(ctx, "你们怎么收费？报个价");
        assertEquals("报价-价格", hit.getRuleName());

        StrategyConfig fallbackHit = service.selectForMessage(ctx, "在吗，随便聊聊");
        assertEquals("兜底-闲聊", fallbackHit.getRuleName());
    }

    @Test
    void noRulesReturnsNullAndRenderReplacesPlaceholders() {
        when(repository.findByStageAndEnabledTrueOrderByPriorityAsc("LEAD_INITIAL")).thenReturn(List.of());
        ContactContext noRulesCtx = ContactContext.builder().contact(contact()).build();
        assertNull(service.selectForMessage(noRulesCtx, "你好"));

        StrategyConfig rule = config(100, null, "报价-价格", "CREATE_QUOTE", "您好 {{customerName}}，报价单号 {{quoteReference}} 已生成，请查收");
        String rendered = service.render(rule, noRulesCtx, "QT-20260904-1");
        assertEquals("您好 张总，报价单号 QT-20260904-1 已生成，请查收", rendered);

        // 无 quoteReference 时占位符保持原样，由调用方决定是否降级文案
        String withoutRef = service.render(rule, noRulesCtx, null);
        assertEquals("您好 张总，报价单号 {{quoteReference}} 已生成，请查收", withoutRef);
    }

    private StrategyConfig config(int priority, String keywords, String name, String actionType, String template) {
        StrategyConfig cfg = new StrategyConfig();
        cfg.setStage("PROPOSAL");
        cfg.setRuleName(name);
        cfg.setTriggerKeywords(keywords);
        cfg.setActionType(actionType);
        cfg.setTemplateContent(template);
        cfg.setPriority(priority);
        cfg.setEnabled(true);
        cfg.touch();
        return cfg;
    }

    private Contact contact() {
        Contact c = new Contact();
        c.setId(1L);
        c.setName("张总");
        return c;
    }

    private Deal deal(SalesStage stage) {
        Deal d = new Deal();
        d.setId(10L);
        d.setContactId(1L);
        d.setStage(stage);
        return d;
    }
}

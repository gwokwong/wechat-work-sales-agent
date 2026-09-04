package com.example.wechatsales.stage;

import com.example.wechatsales.context.ContactContext;
import com.example.wechatsales.domain.Contact;
import com.example.wechatsales.domain.CustomerProfile;
import com.example.wechatsales.domain.SalesStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 规则版阶段分类器单测：最近消息关键词命中 / 终态信号优先 / 画像摘要补充 / 默认初始阶段。
 */
class RuleStageClassifierTest {

    private RuleStageClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new RuleStageClassifier();
    }

    @Test
    void classifiesFromRecentMessagesWithTerminalPriority() {
        // 命中方案/报价关键词
        ContactContext ctx = ctxWithRecent(List.of("你好，想了解一下方案", "大概多少钱？"));
        assertEquals(SalesStage.PROPOSAL, classifier.classify(ctx));

        // 终态 LOST 关键词优先级高于同串中的 PROPOSAL（最近一条优先 + LOST 规则靠前）
        ContactContext lossCtx = ctxWithRecent(List.of("报个价", "算了，太贵了不买了"));
        assertEquals(SalesStage.LOST, classifier.classify(lossCtx));

        // WON 关键词
        ContactContext wonCtx = ctxWithRecent(List.of("行，就选你们，这周走合同"));
        assertEquals(SalesStage.WON, classifier.classify(wonCtx));
    }

    @Test
    void fallsBackToProfileHintAndDefaultInitial() {
        // 最近无信号时，画像需求摘要可作为历史信号
        CustomerProfile profile = new CustomerProfile();
        profile.setNeedsSummary("客户已进入签约流程，准备签合同");
        ContactContext profileCtx = ContactContext.builder()
                .contact(contact())
                .profile(profile)
                .recentCustomerMessages(List.of())
                .build();
        assertEquals(SalesStage.WON, classifier.classify(profileCtx));

        // 完全无信号 → 初始阶段
        ContactContext emptyCtx = ContactContext.builder()
                .contact(contact())
                .recentCustomerMessages(List.of())
                .build();
        assertEquals(SalesStage.LEAD_INITIAL, classifier.classify(emptyCtx));
    }

    private ContactContext ctxWithRecent(List<String> recent) {
        return ContactContext.builder()
                .contact(contact())
                .recentCustomerMessages(recent)
                .build();
    }

    private Contact contact() {
        Contact c = new Contact();
        c.setId(1L);
        c.setExternalUserId("wm001");
        c.setName("张总");
        return c;
    }
}

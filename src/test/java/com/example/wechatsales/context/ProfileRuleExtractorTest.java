package com.example.wechatsales.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 画像规则提取器单测：预算范围 / 时间窗口提取、不可靠时保留空+记录原因、常见误报规避。
 */
class ProfileRuleExtractorTest {

    private final ProfileRuleExtractor extractor = new ProfileRuleExtractor();

    @Test
    void extractsBudgetRangeFromMessage() {
        assertEquals("30-50万", extractor.extract("我们预算 30-50万").budgetRange());
        assertEquals("3到5万", extractor.extract("预算大概是3到5万").budgetRange());
        assertEquals("5万", extractor.extract("预算是 5 万").budgetRange());
        assertEquals("8000元", extractor.extract("大约 8000 元").budgetRange());
        assertEquals("30万", extractor.extract("约为 30 万").budgetRange());
        assertEquals("5000", extractor.extract("预算 5000").budgetRange());
        assertEquals("10-20万", extractor.extract("费用大概 10-20 万左右").budgetRange());
        assertEquals("50万-80万", extractor.extract("价格在 50万 - 80万 之间").budgetRange());
    }

    @Test
    void extractsTimeWindowFromMessage() {
        assertEquals("Q3", extractor.extract("计划 Q3 上线").timeWindow());
        assertEquals("2026年11月底", extractor.extract("希望2026年11月底前实施").timeWindow());
        assertTrue(extractor.extract("下个月开始").timeWindow().contains("下个月"));
        assertTrue(extractor.extract("年底前要定下来").timeWindow().contains("年底"));
        assertTrue(extractor.extract("双十一有活动吗").timeWindow().contains("双十一"));
    }

    @Test
    void keepsNullAndRecordsReasonWhenUnreliable() {
        ProfileRuleExtractor.ProfileExtraction e1 = extractor.extract("不太清楚预算");
        assertNull(e1.budgetRange());
        assertNotNull(e1.budgetSkipReason());

        ProfileRuleExtractor.ProfileExtraction e2 = extractor.extract("计划下周上线");
        assertNull(e2.timeWindow());
        assertNotNull(e2.timeSkipReason());

        // 与价格/时间无关的普通消息：预算/时间窗均保持空且不记录失败原因
        ProfileRuleExtractor.ProfileExtraction e3 = extractor.extract("今天天气不错");
        assertNull(e3.budgetRange());
        assertNull(e3.budgetSkipReason());
        assertNull(e3.timeWindow());
        assertNull(e3.timeSkipReason());
    }

    @Test
    void avoidsFalsePositives() {
        assertNull(extractor.extract("约 30 分钟电话").budgetRange());
        assertNull(extractor.extract("3 到 5 个人使用").budgetRange());
        assertNull(extractor.extract("一通电话聊方案").budgetRange());
    }
}

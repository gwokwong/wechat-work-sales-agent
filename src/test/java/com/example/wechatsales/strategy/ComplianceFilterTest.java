package com.example.wechatsales.strategy;

import com.example.wechatsales.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合规过滤器单测：合规话术放行 / 敏感词 / 空内容 / 超长话术拦截。
 */
class ComplianceFilterTest {

    private ComplianceFilter filter;

    @BeforeEach
    void setUp() {
        // 默认敏感词列表 + maxContentLength=2000 已在 AppProperties 初始化
        filter = new ComplianceFilter(new AppProperties());
    }

    @Test
    void passesCompliantMessage() {
        ComplianceResult result = filter.check("您好，我们产品按企业规模和模块订阅收费，具体可按使用人数测算。");
        assertTrue(result.passed());
    }

    @Test
    void blocksSensitiveBlankAndTooLong() {
        ComplianceResult sensitive = filter.check("这个方案我们保证三天内交付，成功率百分百");
        assertFalse(sensitive.passed());
        assertTrue(sensitive.reason().contains("保证"));

        ComplianceResult blank = filter.check("   ");
        assertFalse(blank.passed());

        String tooLong = "很".repeat(2001);
        ComplianceResult longText = filter.check(tooLong);
        assertFalse(longText.passed());
        assertTrue(longText.reason().contains("超长"));
    }
}

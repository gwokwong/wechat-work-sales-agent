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

    @Test
    void blocksPatternHitWithRuleName() {
        AppProperties props = new AppProperties();
        props.getCompliance().setBlockedPatterns(java.util.List.of("\\b\\d{15,18}\\b", "\\b1[3-9]\\d{9}\\b"));
        props.getCompliance().setPatternRuleNames("银行卡/身份证号码,手机号码");
        ComplianceFilter patternFilter = new ComplianceFilter(props);

        // 内容含 16 位数字（命中第一条银行卡/身份证规则，reason 含规则名）
        ComplianceResult card = patternFilter.check("请把款转到卡号 6222021234567890，谢谢。");
        assertFalse(card.passed());
        assertTrue(card.reason().contains("银行卡/身份证号码"));

        // 内容含手机号（命中第二条手机规则）
        ComplianceResult phone = patternFilter.check("可以打我电话 13812345678 详聊");
        assertFalse(phone.passed());
        assertTrue(phone.reason().contains("手机号码"));
    }

    @Test
    void passesWhenPatternNotHit() {
        AppProperties props = new AppProperties();
        props.getCompliance().setBlockedPatterns(java.util.List.of("\\b\\d{15,18}\\b"));
        props.getCompliance().setPatternRuleNames("银行卡/身份证号码");
        ComplianceFilter patternFilter = new ComplianceFilter(props);

        ComplianceResult ok = patternFilter.check("您好，方案报价单已整理好，请查收。");
        assertTrue(ok.passed());
    }

    @Test
    void invalidPatternSkippedAndOthersStillWork() {
        AppProperties props = new AppProperties();
        // 第一条非法正则（未闭合括号），第二条合法
        props.getCompliance().setBlockedPatterns(java.util.List.of("([0-9]{4", "\\b\\d{15,18}\\b"));
        props.getCompliance().setPatternRuleNames("非法规则,银行卡/身份证号码");
        ComplianceFilter patternFilter = new ComplianceFilter(props);

        // 非法正则不抛异常：未命中号码的正常话术放行
        ComplianceResult pass = patternFilter.check("您好，请问什么时候方便进一步沟通？");
        assertTrue(pass.passed());

        // 其余合法规则仍生效
        ComplianceResult hit = patternFilter.check("请发卡号 6222021234567890 到邮箱");
        assertFalse(hit.passed());
        assertTrue(hit.reason().contains("银行卡/身份证号码"));
    }
}

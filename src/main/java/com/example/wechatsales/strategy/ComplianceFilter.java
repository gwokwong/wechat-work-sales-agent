package com.example.wechatsales.strategy;

import com.example.wechatsales.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 合规过滤器：发送前强制校验 —— 敏感词、绝对化承诺、正则规则（号码/格式类）、超长截断风险。
 * 未通过的话术置为 BLOCKED，由人工介入修改后重新审批。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ComplianceFilter {

    private final AppProperties appProperties;

    public ComplianceResult check(String content) {
        if (content == null || content.isBlank()) {
            return ComplianceResult.blocked("话术为空，拒绝发送");
        }
        String text = content.toLowerCase(Locale.ROOT);
        int maxLen = appProperties.getCompliance().getMaxContentLength();

        for (String word : sensitiveWords()) {
            if (word != null && !word.isBlank() && text.contains(word.toLowerCase(Locale.ROOT))) {
                return ComplianceResult.blocked("命中敏感词「" + word + "」，请人工调整措辞");
            }
        }

        // 正则规则外置：命中即阻断（银行卡/身份证/手机号等号码或格式类敏感内容）
        ComplianceResult patternHit = checkPatterns(content);
        if (!patternHit.passed()) {
            return patternHit;
        }

        if (content.length() > maxLen) {
            return ComplianceResult.blocked("话术超长（" + content.length() + " > " + maxLen + "），请精简");
        }
        return ComplianceResult.PASSED;
    }

    private ComplianceResult checkPatterns(String content) {
        List<String> patterns = appProperties.getCompliance().getBlockedPatterns();
        if (patterns == null || patterns.isEmpty()) {
            return ComplianceResult.PASSED;
        }
        List<String> ruleNames = splitRuleNames(appProperties.getCompliance().getPatternRuleNames());

        for (int i = 0; i < patterns.size(); i++) {
            String regex = patterns.get(i);
            if (regex == null || regex.isBlank()) {
                continue;
            }
            final Pattern compiled;
            try {
                compiled = Pattern.compile(regex);
            } catch (PatternSyntaxException e) {
                // 单条正则非法不阻断主流程：记录 WARN 后跳过该条
                log.warn("[ComplianceFilter] 忽略非法正则规则 blockedPatterns[{}]=\"{}\" : {}", i, regex, e.getMessage());
                continue;
            }
            if (compiled.matcher(content).find()) {
                String name = ruleName(ruleNames, i, regex);
                return ComplianceResult.blocked("命中合规规则「" + name + "」，请勿发送含敏感号码/格式的内容");
            }
        }
        return ComplianceResult.PASSED;
    }

    private List<String> splitRuleNames(String raw) {
        List<String> names = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return names;
        }
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                names.add(trimmed);
            }
        }
        return names;
    }

    /** 取第 i 条规则名；未配置（或数量不足）时回退正则原文 */
    private String ruleName(List<String> ruleNames, int index, String regex) {
        if (index < ruleNames.size()) {
            return ruleNames.get(index);
        }
        return regex;
    }

    private List<String> sensitiveWords() {
        return appProperties.getCompliance().getSensitiveWords();
    }
}

package com.example.wechatsales.strategy;

import com.example.wechatsales.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 合规过滤器：发送前强制校验 —— 敏感词、绝对化承诺、超长截断风险。
 * 未通过的话术置为 BLOCKED，由人工介入修改后重新审批。
 */
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
        if (content.length() > maxLen) {
            return ComplianceResult.blocked("话术超长（" + content.length() + " > " + maxLen + "），请精简");
        }
        return ComplianceResult.PASSED;
    }

    private List<String> sensitiveWords() {
        return appProperties.getCompliance().getSensitiveWords();
    }
}

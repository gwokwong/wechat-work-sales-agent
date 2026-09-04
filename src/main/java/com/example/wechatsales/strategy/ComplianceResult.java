package com.example.wechatsales.strategy;

import com.example.wechatsales.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/** 合规校验结果 */
public record ComplianceResult(boolean passed, String reason) {

    public static final ComplianceResult PASSED = new ComplianceResult(true, null);

    public static ComplianceResult blocked(String reason) {
        return new ComplianceResult(false, reason);
    }
}

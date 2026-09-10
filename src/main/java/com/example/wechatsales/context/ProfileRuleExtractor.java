package com.example.wechatsales.context;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 画像规则提取器：从客户消息文本中用轻量正则启发式提取「预算范围 budget_range」
 * 与「时间窗口 time_window」，供 {@link CustomerProfileService} 沉淀到画像
 * （DESIGN.md §5.3）。
 *
 * <p>纯正则、不依赖 LLM/外部服务；无法可靠提取时预算/时间窗返回 {@code null}，
 * 并在对应的 {@code *SkipReason} 说明原因，由调用方落日志、保留字段为空。</p>
 */
@Component
public class ProfileRuleExtractor {

    /** 预算提取结果：budgetRange/timeWindow 为提取到的原文片段，null 表示未提取；对应 SkipReason 说明原因 */
    public record ProfileExtraction(String budgetRange, String timeWindow,
                                    String budgetSkipReason, String timeSkipReason) {
    }

    /** 价格/预算信号词：只要出现即视为"可能涉及价格"，用于判定"提取失败需记录原因" */
    private static final Pattern BUDGET_SIGNAL =
            Pattern.compile("预算|价格|价位|费用|报价|多少钱|成本|投入|金额|万|元|千|[wWkK]");

    /** 金额区间（至少一端带金额单位，避免 "3 到 5 个人" 误报）：30-50万 / 30~50万 / 8千-1万2 */
    private static final Pattern AMOUNT_RANGE_WITH_UNIT =
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(万|w|W|千|k|K|元)?\\s*[-~—–到至]\\s*(\\d+(?:\\.\\d+)?)\\s*(万|w|W|千|k|K|元)");

    /** 两端均无单位的裸区间，仅当紧跟预算语境词：预算 3 到 5 / 费用 30-40 */
    private static final Pattern AMOUNT_RANGE_BARE =
            Pattern.compile("(?:预算是|预算|报价|费用|金额|价格|价位|成本|投入)\\s*(\\d+(?:\\.\\d+)?)\\s*[-~—–到至]\\s*(\\d+(?:\\.\\d+)?)");

    /** 带单位单值预算：预算 5 万 / 大约 8000 元 / 约为 30 万 */
    private static final Pattern AMOUNT_SINGLE_WITH_UNIT =
            Pattern.compile("(?:预算是|预算|大概|大约|约为|约|差不多|将近|不超过)\\s*(\\d+(?:\\.\\d+)?)\\s*(万|w|W|千|k|K|元)");

    /** 无单位单值预算，仅当明确"预算"语义：预算 5000（负向前瞻避免与带单位重复捕获） */
    private static final Pattern AMOUNT_SINGLE_BARE =
            Pattern.compile("(?:预算是|预算)\\s*(\\d+(?:\\.\\d+)?)(?!\\s*(万|w|W|千|k|K|元))");

    /** 时间窗口信号词：出现即视为可能涉及时间，用于"提取失败需记录原因" */
    private static final Pattern TIME_SIGNAL =
            Pattern.compile("时间|上线|计划|什么时候|几月|季度|月底|年底|明年|下个月|本月|这个月|双十一|双11|双十二|双12|618");

    /** 时间窗口规则（按序匹配，任一命中即提取该片段） */
    private static final List<Pattern> TIME_PATTERNS = List.of(
            // Q1~Q4 / 第X季度 / X季度
            Pattern.compile("([Qq][1-4])|(第\\s*[一二三四1-4]\\s*季度)|([一二三四]季度)"),
            // 2026年11月 / 9月底 / 11月份
            Pattern.compile("(20\\d{2}\\s*年?\\s*)?([1-9]|1[0-2])\\s*月(?:份|底|上旬|中旬|下旬|初|末)?"),
            // 今年年底 / 年底前 / 明年
            Pattern.compile("(今年\\s*)?(年底|年末)|(明年)|(下个月|这个月|本月)"),
            // 促销节点
            Pattern.compile("(双十一|双11|双十二|双12|618)")
    );

    /** 从单条客户消息提取预算范围与时间窗口；无文本信号时全部返回 null */
    public ProfileExtraction extract(String text) {
        if (text == null || text.isBlank()) {
            return new ProfileExtraction(null, null, null, null);
        }
        String budget = extractBudget(text);
        String time = extractTimeWindow(text);

        String budgetReason = null;
        if (budget == null && BUDGET_SIGNAL.matcher(text).find()) {
            budgetReason = "含价格/预算信号词但未提取到明确金额区间";
        }
        String timeReason = null;
        if (time == null && TIME_SIGNAL.matcher(text).find()) {
            timeReason = "含时间信号词但未提取到明确时间窗口(如季度/月份/年底/促销节点)";
        }
        return new ProfileExtraction(budget, time, budgetReason, timeReason);
    }

    private String extractBudget(String text) {
        // 1) 区间且至少一端带金额单位（无前缀，直接取原文）
        Matcher range = AMOUNT_RANGE_WITH_UNIT.matcher(text);
        if (range.find()) {
            return normalizeBudget(range.group(0));
        }
        // 2) 预算语境下的裸区间（剥掉前缀词，保留分隔符）
        range = AMOUNT_RANGE_BARE.matcher(text);
        if (range.find()) {
            String stripped = range.group(0)
                    .replaceFirst("^(?:预算是|预算|报价|费用|金额|价格|价位|成本|投入)\\s*", "");
            return normalizeBudget(stripped);
        }
        // 3) 带单位单值（仅取"数字+单位"，规避前缀词交替/定界问题）
        Matcher single = AMOUNT_SINGLE_WITH_UNIT.matcher(text);
        if (single.find()) {
            return normalizeBudget(single.group(1) + single.group(2));
        }
        // 4) 明确"预算"语义下的无单位单值
        single = AMOUNT_SINGLE_BARE.matcher(text);
        if (single.find()) {
            return normalizeBudget(single.group(1));
        }
        return null;
    }

    private String extractTimeWindow(String text) {
        Set<String> hits = new LinkedHashSet<>();
        for (Pattern p : TIME_PATTERNS) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                String hit = m.group(0).trim();
                if (!hit.isBlank()) {
                    hits.add(hit);
                }
            }
        }
        if (hits.isEmpty()) {
            return null;
        }
        List<String> top = new ArrayList<>(hits);
        int max = Math.min(3, top.size());
        return String.join(" / ", top.subList(0, max));
    }

    /** 单位归一化：w/W→万、k/K→千，便于统一阅读 */
    private String normalizeBudget(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        return raw.replaceAll("[wW](?=\\s*[-~—–到至]|$)", "万")
                .replaceAll("[kK](?=\\s*[-~—–到至]|$)", "千")
                .replaceAll("\\s+", "")
                .trim();
    }
}

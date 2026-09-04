package com.example.wechatsales.stage;

import com.example.wechatsales.context.ContactContext;
import com.example.wechatsales.domain.SalesStage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 规则版阶段识别器：基于最近对话与画像的关键词规则，按优先级判断阶段。
 * 关键词命中即视为该阶段信号（演示用；真实可引入加权评分与 LLM 校验）。
 *
 * <p>优先级：终态信号 > 方案/报价 > 需求分析 > 资质确认 > 初始</p>
 */
@Component
public class RuleStageClassifier implements StageClassifier {

    /** 关键触发规则：stage → 触发词列表 */
    static List<StageRule> rules() {
        List<StageRule> list = new ArrayList<>();
        // 终态
        list.add(new StageRule(SalesStage.LOST, List.of("不买了", "算了", "不需要了", "太贵了", "再看看吧", "暂缓", "别联系了", "没预算")));
        list.add(new StageRule(SalesStage.WON, List.of("成交", "签合同", "合同盖章", "下单", "付定金", "确定合作", "就选你们")));
        // 谈判 / 报价
        list.add(new StageRule(SalesStage.NEGOTIATION, List.of("再优惠", "折扣", "送个", "送一年", "赠品", "便宜点", "价格可以再", "能不能优惠", "抹个零")));
        list.add(new StageRule(SalesStage.PROPOSAL, List.of("出方案", "方案看看", "出个方案", "报价", "什么价格", "多少钱", "怎么收费", "费用多少", "预算", "报个价", "看下价格", "价格表")));
        // 需求分析 / 资质
        list.add(new StageRule(SalesStage.NEEDS_ANALYSIS, List.of("需要", "想了解", "怎么用", "能不能", "有什么功能", "适合我们", "怎么对接", "要支持", "要求", "场景", "门店", "连锁")));
        list.add(new StageRule(SalesStage.QUALIFIED, List.of("我们公司", "我们团队", "我们有", "感兴趣", "想深入", "了解下", "介绍下", "咨询")));
        return list;
    }

    @Override
    public SalesStage classify(ContactContext context) {
        // 最近对话信号（新消息优先，旧→新循环保证最新信号在末尾，从后往前优先）
        List<String> recent = context.getRecentCustomerMessages();
        List<StageRule> ordered = rules();
        for (int i = recent.size() - 1; i >= 0; i--) {
            String text = normalize(recent.get(i));
            for (StageRule rule : ordered) {
                if (rule.matches(text)) {
                    return rule.stage();
                }
            }
        }
        // 画像需求摘要补充（历史信号弱化为优先级判断，防止反复震荡）
        String needs = context.getProfile() == null ? "" : normalize(context.getProfile().getNeedsSummary());
        if (!needs.isEmpty()) {
            for (StageRule rule : ordered) {
                if (rule.matches(needs)) {
                    return rule.stage();
                }
            }
        }
        return SalesStage.LEAD_INITIAL;
    }

    private String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    /** 规则条目 */
    record StageRule(SalesStage stage, List<String> keywords) {
        boolean matches(String text) {
            for (String k : keywords) {
                if (text.contains(k)) {
                    return true;
                }
            }
            return false;
        }
    }
}

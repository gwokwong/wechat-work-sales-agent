package com.example.wechatsales.domain;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Set;

/**
 * 销售阶段状态机枚举。
 *
 * <p>主路径：LEAD_INITIAL → QUALIFIED → NEEDS_ANALYSIS → PROPOSAL → NEGOTIATION → WON
 * 任意非终态均可跃迁到 LOST（输单）。</p>
 *
 * <p>跃迁规则（canTransitionTo）：
 * <ul>
 *   <li>允许沿主路径向后移动，且允许小幅跳级（如 LEAD_INITIAL → NEEDS_ANALYSIS，避免规则引擎偶尔漏掉中间阶段卡死流水）；</li>
 *   <li>非终态允许进入 LOST，进入后不可回退；</li>
 *   <li>WON / LOST 为终态，禁止再次跃迁（防止对已赢单/流失客户重复推进）。</li>
 * </ul></p>
 */
public enum SalesStage {

    LEAD_INITIAL("初始线索", false),
    QUALIFIED("已确认意向", false),
    NEEDS_ANALYSIS("需求分析", false),
    PROPOSAL("方案报价", false),
    NEGOTIATION("商务谈判", false),
    WON("赢单", true),
    LOST("输单/流失", true);

    private final String displayName;
    private final boolean terminal;

    SalesStage(String displayName, boolean terminal) {
        this.displayName = displayName;
        this.terminal = terminal;
    }

    private static final EnumMap<SalesStage, Set<SalesStage>> TRANSITIONS = new EnumMap<>(SalesStage.class);

    static {
        put(LEAD_INITIAL, QUALIFIED, NEEDS_ANALYSIS, LOST);
        put(QUALIFIED, NEEDS_ANALYSIS, PROPOSAL, NEGOTIATION, LOST);
        put(NEEDS_ANALYSIS, PROPOSAL, NEGOTIATION, LOST);
        put(PROPOSAL, NEGOTIATION, WON, LOST);
        put(NEGOTIATION, WON, LOST);
        put(WON); // 终态：不允许再跃迁
        put(LOST); // 终态：不允许再跃迁
    }

    private static void put(SalesStage from, SalesStage... tos) {
        Set<SalesStage> set = new HashSet<>(Set.of(tos));
        TRANSITIONS.put(from, set);
    }

    public String displayName() {
        return displayName;
    }

    public boolean isTerminal() {
        return terminal;
    }

    /** 是否允许从当前阶段跃迁到目标阶段 */
    public boolean canTransitionTo(SalesStage target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }
}

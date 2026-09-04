package com.example.wechatsales.strategy;

import com.example.wechatsales.context.ContactContext;

/**
 * LLM 客户端抽象：话术生成 / 润色统一走本接口。
 * M0 使用 {@link MockLLMClient}；M1+ 实现本接口接入真实大模型（DeepSeek/混元/自建网关等），
 * 只需实现 generate 并切换 bean 即可，业务编排层无感知。
 */
public interface LLMClient {

    /**
     * 生成/润色一条销售话术。
     *
     * @param ctx       客户上下文快照（画像/阶段/最近对话）
     * @param strategy  命中策略（含模板与动作类型）
     * @param draft     已按模板渲染的基础话术（LLM 可在此基础上做个性化润色）
     * @return 最终话术文本
     */
    String generate(ContactContext ctx, StrategyConfig strategy, String draft);

    default String implName() {
        return getClass().getSimpleName();
    }
}

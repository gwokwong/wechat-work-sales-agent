package com.example.wechatsales.stage;

import com.example.wechatsales.context.ContactContext;
import com.example.wechatsales.domain.SalesStage;

/**
 * 销售阶段识别器：从客户上下文判断当前应处于哪个销售阶段。
 * 演进路径：规则 → LLM 分类 → 规则+LLM 混合（置信度裁决）。
 */
public interface StageClassifier {

    SalesStage classify(ContactContext context);

    /** 实现类型标识（rule / llm / hybrid），便于日志与演示展示 */
    default String implName() {
        return getClass().getSimpleName();
    }
}

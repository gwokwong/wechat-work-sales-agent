package com.example.wechatsales.exception;

import com.example.wechatsales.domain.SalesStage;

/** 销售阶段非法跃迁 */
public class StageTransitionException extends BusinessException {

    public StageTransitionException(SalesStage from, SalesStage to) {
        super(String.format("非法销售阶段跃迁：%s(%s) → %s(%s)，当前状态机不允许该路径",
                from.name(), from.displayName(), to.name(), to.displayName()));
    }
}

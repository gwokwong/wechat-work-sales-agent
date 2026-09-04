package com.example.wechatsales.stage;

import com.example.wechatsales.action.ActionLogger;
import com.example.wechatsales.domain.Deal;
import com.example.wechatsales.domain.SalesStage;
import com.example.wechatsales.exception.StageTransitionException;
import com.example.wechatsales.repository.DealRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 销售阶段状态机：校验 SalesStage 枚举定义的跃迁表，驱动 Deal 生命周期。
 * 演示链路中由 DealService 统一调用本机执行阶段推进。
 */
@Service
@RequiredArgsConstructor
public class StageMachine {

    private final DealRepository dealRepository;
    private final ActionLogger actionLogger;

    /**
     * 尝试将 deal 从当前阶段跃迁到 to。
     * 非法跃迁抛 {@link StageTransitionException}；跃迁成功更新商机时间戳并落动作日志。
     */
    public Deal transition(Deal deal, SalesStage to) {
        SalesStage from = deal.getStage();
        if (from == to) {
            return deal;
        }
        if (!from.canTransitionTo(to)) {
            throw new StageTransitionException(from, to);
        }
        deal.setStage(to);
        deal.touch();
        if (to.isTerminal()) {
            deal.setClosedAt(LocalDateTime.now());
            deal.setClosedReason(to == SalesStage.WON ? "成交赢单" : "输单/流失");
        }
        dealRepository.save(deal);
        actionLogger.log(deal.getContactId(), "STAGE_TRANSITION",
                "Deal#" + deal.getId() + " " + from.name() + " → " + to.name());
        return deal;
    }
}

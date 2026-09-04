package com.example.wechatsales.stage;

import com.example.wechatsales.action.ActionLogger;
import com.example.wechatsales.domain.Deal;
import com.example.wechatsales.domain.SalesStage;
import com.example.wechatsales.exception.StageTransitionException;
import com.example.wechatsales.repository.DealRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 销售阶段状态机单测：合法跃迁落库+审计 / 同阶段幂等 / 非法跃迁抛异常不落库。
 */
@ExtendWith(MockitoExtension.class)
class StageMachineTest {

    @Mock
    private DealRepository dealRepository;
    @Mock
    private ActionLogger actionLogger;

    private StageMachine stageMachine;

    @BeforeEach
    void setUp() {
        stageMachine = new StageMachine(dealRepository, actionLogger);
    }

    @Test
    void legalTransitionPersistsAndAuditsSameStageIsIdempotent() {
        Deal deal = dealOf(SalesStage.QUALIFIED, 10L, 1L);

        // 同阶段调用：直接返回，不落库不审计
        Deal same = stageMachine.transition(deal, SalesStage.QUALIFIED);
        assertSame(deal, same);
        verify(dealRepository, never()).save(deal);

        // 合法跃迁 QUALIFIED → PROPOSAL：更新阶段、时间戳、落库并审计
        Deal updated = stageMachine.transition(deal, SalesStage.PROPOSAL);
        assertSame(deal, updated);
        assertEquals(SalesStage.PROPOSAL, updated.getStage());
        verify(dealRepository).save(deal);
        verify(actionLogger).log(1L, "STAGE_TRANSITION",
                "Deal#10 QUALIFIED → PROPOSAL");
    }

    @Test
    void illegalTransitionThrowsAndDoesNotPersist() {
        Deal deal = dealOf(SalesStage.PROPOSAL, 11L, 2L);

        assertThrows(StageTransitionException.class,
                () -> stageMachine.transition(deal, SalesStage.QUALIFIED));
        assertEquals(SalesStage.PROPOSAL, deal.getStage());
        verify(dealRepository, never()).save(deal);
        verify(actionLogger, never()).log(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    private Deal dealOf(SalesStage stage, Long dealId, Long contactId) {
        Deal deal = new Deal();
        deal.setId(dealId);
        deal.setContactId(contactId);
        deal.setDealName("商机-" + contactId);
        deal.setStage(stage);
        deal.touch();
        return deal;
    }
}

package com.example.wechatsales.stage;

import com.example.wechatsales.domain.Contact;
import com.example.wechatsales.domain.Deal;
import com.example.wechatsales.domain.SalesStage;
import com.example.wechatsales.exception.StageTransitionException;
import com.example.wechatsales.repository.DealRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商机服务单测：找/建商机复用、同阶段/终态/非法建议的处理与成功推进。
 */
@ExtendWith(MockitoExtension.class)
class DealServiceTest {

    @Mock
    private DealRepository dealRepository;
    @Mock
    private StageMachine stageMachine;

    private DealService dealService;

    @BeforeEach
    void setUp() {
        dealService = new DealService(dealRepository, stageMachine);
    }

    @Test
    void getOrCreateDealCreatesThenReusesExisting() {
        when(dealRepository.findByContactId(1L)).thenReturn(Optional.empty());
        when(dealRepository.save(any(Deal.class))).thenAnswer(inv -> {
            Deal d = inv.getArgument(0);
            d.setId(100L);
            return d;
        });

        Deal created = dealService.getOrCreateDeal(1L);
        assertNotNull(created.getId());
        assertEquals(SalesStage.LEAD_INITIAL, created.getStage());
        assertEquals(1L, created.getContactId());
        verify(dealRepository).save(any(Deal.class));

        when(dealRepository.findByContactId(1L)).thenReturn(Optional.of(created));
        Deal reused = dealService.getOrCreateDeal(1L);
        assertSame(created, reused);
        // save stub 就地回填 id 并返回同一引用（首次保存对象即 created）；
        // 复用分支不应触发第二次 save
        verify(dealRepository, times(1)).save(any(Deal.class));
    }

    @Test
    void applyClassifiedStageGuardsSameStageTerminalAndIllegal() {
        Contact contact1 = contact(1L, "wm001");
        Deal sameStage = dealOf(2L, 1L, SalesStage.QUALIFIED);
        when(dealRepository.findByContactId(1L)).thenReturn(Optional.of(sameStage));
        DealService.StageUpdateResult same = dealService.applyClassifiedStage(contact1, SalesStage.QUALIFIED);
        assertFalse(same.changed());
        assertEquals(SalesStage.QUALIFIED, same.fromStage());
        verify(stageMachine, never()).transition(any(), any());

        Contact contact2 = contact(2L, "wm002");
        Deal terminal = dealOf(3L, 2L, SalesStage.WON);
        when(dealRepository.findByContactId(2L)).thenReturn(Optional.of(terminal));
        DealService.StageUpdateResult terminalRes = dealService.applyClassifiedStage(contact2, SalesStage.PROPOSAL);
        assertFalse(terminalRes.changed());
        assertTrue(terminalRes.blockedReason().contains("终态"));

        Contact contact3 = contact(3L, "wm003");
        Deal illegal = dealOf(4L, 3L, SalesStage.PROPOSAL);
        when(dealRepository.findByContactId(3L)).thenReturn(Optional.of(illegal));
        when(stageMachine.transition(illegal, SalesStage.QUALIFIED))
                .thenThrow(new StageTransitionException(SalesStage.PROPOSAL, SalesStage.QUALIFIED));
        DealService.StageUpdateResult illegalRes = dealService.applyClassifiedStage(contact3, SalesStage.QUALIFIED);
        assertFalse(illegalRes.changed());
        assertTrue(illegalRes.blockedReason().contains("非法"));

        Contact contact4 = contact(4L, "wm004");
        Deal forward = dealOf(5L, 4L, SalesStage.LEAD_INITIAL);
        when(dealRepository.findByContactId(4L)).thenReturn(Optional.of(forward));
        when(stageMachine.transition(forward, SalesStage.QUALIFIED)).thenAnswer(inv -> {
            forward.setStage(SalesStage.QUALIFIED);
            return forward;
        });
        DealService.StageUpdateResult ok = dealService.applyClassifiedStage(contact4, SalesStage.QUALIFIED);
        assertTrue(ok.changed());
        assertEquals(SalesStage.QUALIFIED, ok.deal().getStage());
    }

    private Contact contact(Long id, String externalId) {
        Contact c = new Contact();
        c.setId(id);
        c.setExternalUserId(externalId);
        c.setName("客户-" + id);
        c.touch();
        return c;
    }

    private Deal dealOf(Long dealId, Long contactId, SalesStage stage) {
        Deal d = new Deal();
        d.setId(dealId);
        d.setContactId(contactId);
        d.setDealName("商机-" + contactId);
        d.setStage(stage);
        d.touch();
        return d;
    }
}

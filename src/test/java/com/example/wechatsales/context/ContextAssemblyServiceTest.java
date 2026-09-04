package com.example.wechatsales.context;

import com.example.wechatsales.domain.Contact;
import com.example.wechatsales.domain.CustomerProfile;
import com.example.wechatsales.domain.Deal;
import com.example.wechatsales.domain.SalesStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 上下文组装服务单测：组装完整快照与文本摘要、无商机时阶段兜底。
 */
@ExtendWith(MockitoExtension.class)
class ContextAssemblyServiceTest {

    @Mock
    private ContextStore contextStore;

    private ContextAssemblyService service;

    @BeforeEach
    void setUp() {
        service = new ContextAssemblyService(contextStore);
    }

    @Test
    void assembleBuildsCompleteSnapshotAndRenderSummary() {
        Contact contact = new Contact();
        contact.setId(1L);
        contact.setName("王总");
        contact.setCompany("XX连锁");

        CustomerProfile profile = new CustomerProfile();
        profile.setNeedsSummary("需要标准版并希望对接现有会员系统");

        Deal deal = new Deal();
        deal.setId(10L);
        deal.setDealName("商机-1");
        deal.setStage(SalesStage.PROPOSAL);
        deal.touch();

        when(contextStore.getOrCreateContactByInternalId(1L)).thenReturn(contact);
        when(contextStore.getOrCreateProfile(1L)).thenReturn(profile);
        when(contextStore.findDeal(1L)).thenReturn(Optional.of(deal));
        when(contextStore.recentCustomerMessages(1L, ContextStore.DEFAULT_RECENT_LIMIT))
                .thenReturn(List.of("想了解下方案", "大概多少钱？"));

        ContactContext ctx = service.assemble(1L);

        assertEquals(contact, ctx.getContact());
        assertEquals(profile, ctx.getProfile());
        assertEquals(SalesStage.PROPOSAL, service.currentStageOf(ctx));
        assertEquals(2, ctx.getRecentCustomerMessages().size());
        assertEquals(ContextStore.DEFAULT_RECENT_LIMIT, ctx.getRecentLimit());

        String summary = service.renderSummary(ctx);
        assertTrue(summary.contains("王总"));
        assertTrue(summary.contains("XX连锁"));
        assertTrue(summary.contains("会员系统"));
        assertTrue(summary.contains("PROPOSAL"));
        assertTrue(summary.contains("2 轮"));
        assertTrue(summary.contains("大概多少钱？"));
    }

    @Test
    void currentStageFallsBackToInitialWhenNoDeal() {
        Contact contact = new Contact();
        contact.setId(2L);
        contact.setName("李总");
        when(contextStore.getOrCreateContactByInternalId(2L)).thenReturn(contact);
        when(contextStore.getOrCreateProfile(2L)).thenReturn(new CustomerProfile());
        when(contextStore.findDeal(2L)).thenReturn(Optional.empty());
        when(contextStore.recentCustomerMessages(2L, ContextStore.DEFAULT_RECENT_LIMIT)).thenReturn(List.of());

        ContactContext ctx = service.assemble(2L);
        assertNull(ctx.getDeal());
        assertEquals(SalesStage.LEAD_INITIAL, service.currentStageOf(ctx));
        assertTrue(service.renderSummary(ctx).contains("李总"));
    }
}

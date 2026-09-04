package com.example.wechatsales.action;

import com.example.wechatsales.domain.QuoteRequest;
import com.example.wechatsales.repository.QuoteRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mock 报价服务单测：默认产品/数量、金额测算、落库记录、重复调用各自独立成单。
 */
@ExtendWith(MockitoExtension.class)
class MockQuoteServiceTest {

    @Mock
    private QuoteRequestRepository quoteRequestRepository;

    private MockQuoteService service;

    @BeforeEach
    void setUp() {
        service = new MockQuoteService(quoteRequestRepository);
    }

    @Test
    void defaultsAndPersistsQuoteWithBizRefNo() {
        when(quoteRequestRepository.save(any(QuoteRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        QuoteRequest req = new QuoteRequest();
        req.setContactId(1L);
        // 数量/产品留空 → 默认 1 / CRM-STD

        QuoteResult result = service.createQuote(req);

        assertEquals(new BigDecimal("19800"), result.amount());
        assertEquals("SYNCED", result.status());
        assertTrue(result.quoteNo().startsWith("MOCK-QT-"));

        ArgumentCaptor<QuoteRequest> captor = ArgumentCaptor.forClass(QuoteRequest.class);
        verify(quoteRequestRepository).save(captor.capture());
        QuoteRequest saved = captor.getValue();
        assertEquals(1L, saved.getContactId());
        assertEquals(1, saved.getQuantity());
        assertEquals("CRM-STD", saved.getProductCode());
        assertEquals("CREATED", saved.getStatus());
        assertEquals(result.quoteNo(), saved.getBizRefNo());
        assertNotNull(saved.getCreatedAt());
    }

    @Test
    void customQuantityCalculatesAmountAndEachCallPersistsOwnRecord() {
        when(quoteRequestRepository.save(any(QuoteRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        QuoteRequest req = new QuoteRequest();
        req.setContactId(2L);
        req.setProductCode("CRM-ENT");
        req.setQuantity(3);
        QuoteResult first = service.createQuote(req);

        // 19800 * 3 = 59400
        assertEquals(0, new BigDecimal("59400").compareTo(first.amount()));
        assertTrue(first.quoteNo().startsWith("MOCK-QT-"));

        // 再次创建（模拟幂等重试/并发另开一单）：保存独立新记录而非覆盖旧单
        QuoteRequest req2 = new QuoteRequest();
        req2.setContactId(2L);
        QuoteResult second = service.createQuote(req2);

        ArgumentCaptor<QuoteRequest> captor = ArgumentCaptor.forClass(QuoteRequest.class);
        verify(quoteRequestRepository, times(2)).save(captor.capture());
        List<QuoteRequest> all = captor.getAllValues();
        assertEquals(2, all.size());
        assertEquals(3, all.get(0).getQuantity());
        assertEquals("CRM-ENT", all.get(0).getProductCode());
        assertEquals(1, all.get(1).getQuantity());
        assertEquals("CRM-STD", all.get(1).getProductCode());
        assertNotNull(all.get(0).getBizRefNo());
        assertNotNull(all.get(1).getBizRefNo());
        assertTrue(all.get(1).getBizRefNo().startsWith("MOCK-QT-"));
        assertNotNull(second.quoteNo());
    }
}

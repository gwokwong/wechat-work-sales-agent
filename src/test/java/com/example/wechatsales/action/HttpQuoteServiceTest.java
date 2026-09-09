package com.example.wechatsales.action;

import com.example.wechatsales.config.AppProperties;
import com.example.wechatsales.domain.QuoteRequest;
import com.example.wechatsales.repository.QuoteRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.notNullValue;

/**
 * HttpQuoteService 单测：用 MockRestServiceServer 拦截 RestClient 真实 HTTP 层，
 * 覆盖成功回填落库（携带幂等键）/ 业务失败 / HTTP 5xx 指数退避重试后成功 / 连续失败兜底。
 * 重试用例使用 {@link NoBackoffService} 将退避置 0，避免真实等待。
 */
@ExtendWith(MockitoExtension.class)
class HttpQuoteServiceTest {

    private static final String BASE_URL = "http://quote.internal.test";

    @Mock
    private QuoteRequestRepository quoteRequestRepository;

    private AppProperties appProperties;
    private RestClient.Builder restClientBuilder;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.getQuote().getHttp().setEnabled(true);
        appProperties.getQuote().getHttp().setBaseUrl(BASE_URL);
        appProperties.getQuote().getHttp().setApiKey("secret-key");
        restClientBuilder = RestClient.builder();
    }

    @Test
    void successPostsJsonAndPersistsBizRefNo() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        HttpQuoteService service = new HttpQuoteService(quoteRequestRepository, appProperties, restClientBuilder);
        when(quoteRequestRepository.save(any(QuoteRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        server.expect(requestTo(BASE_URL + HttpQuoteService.CREATE_QUOTE_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpQuoteService.API_KEY_HEADER, "secret-key"))
                .andExpect(content().json("{\"contactId\":2,\"productCode\":\"CRM-ENT\",\"quantity\":3}"))
                .andRespond(withSuccess(
                        "{\"code\":\"SUCCESS\",\"message\":\"ok\",\"bizRefNo\":\"QT-20260904-001\",\"amount\":59400.00}",
                        MediaType.APPLICATION_JSON));

        QuoteRequest req = new QuoteRequest();
        req.setContactId(2L);
        req.setProductCode("CRM-ENT");
        req.setQuantity(3);

        QuoteResult result = service.createQuote(req);

        assertEquals("QT-20260904-001", result.quoteNo());
        assertEquals(0, new BigDecimal("59400.00").compareTo(result.amount()));
        assertEquals("SYNCED", result.status());

        ArgumentCaptor<QuoteRequest> captor = ArgumentCaptor.forClass(QuoteRequest.class);
        verify(quoteRequestRepository, times(2)).save(captor.capture());
        List<QuoteRequest> records = captor.getAllValues();
        // JPA save 返回同一托管实例，二次保存会就地覆盖状态为最终态
        QuoteRequest finalRecord = records.get(records.size() - 1);
        assertEquals("SYNCED", finalRecord.getStatus());
        assertEquals("QT-20260904-001", finalRecord.getBizRefNo());
        assertEquals(0, new BigDecimal("59400.00").compareTo(finalRecord.getAmount()));
        // 幂等键已生成并落库（首个 CREATED 记录同样携带），无重试发生
        assertNotNull(finalRecord.getIdempotencyKey());
        assertTrue(finalRecord.getIdempotencyKey().matches("[0-9a-fA-F-]{36}"));
        assertEquals(0, finalRecord.getRetryCount());
        server.verify();
    }

    @Test
    void businessFailureAndHttp500ReturnFailedResultWithPersistedRecord() {
        // 场景 1：业务系统返回失败 code
        MockRestServiceServer server1 = MockRestServiceServer.bindTo(restClientBuilder).build();
        HttpQuoteService service1 = new HttpQuoteService(quoteRequestRepository, appProperties, restClientBuilder);
        when(quoteRequestRepository.save(any(QuoteRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        server1.expect(requestTo(BASE_URL + HttpQuoteService.CREATE_QUOTE_PATH))
                .andRespond(withSuccess("{\"code\":\"ERROR\",\"message\":\"库存不足\",\"bizRefNo\":null,\"amount\":null}",
                        MediaType.APPLICATION_JSON));

        QuoteRequest req1 = new QuoteRequest();
        req1.setContactId(1L);
        QuoteResult failed = service1.createQuote(req1);
        assertEquals("FAILED", failed.status());
        assertNull(failed.quoteNo());
        assertTrue(failed.message().contains("库存不足"));

        ArgumentCaptor<QuoteRequest> captor1 = ArgumentCaptor.forClass(QuoteRequest.class);
        verify(quoteRequestRepository, times(2)).save(captor1.capture());
        assertEquals("FAILED", captor1.getAllValues().get(1).getStatus());
        server1.verify();
        clearInvocations(quoteRequestRepository); // 隔离场景 2 的 save 次数统计

        // 场景 2：HTTP 500 连续 3 次（maxAttempts=3）全部失败 -> FAILED + lastError，总调用 == maxAttempts
        MockRestServiceServer server2 = MockRestServiceServer.bindTo(restClientBuilder).build();
        HttpQuoteService service2 = new NoBackoffService(quoteRequestRepository, appProperties, restClientBuilder);
        for (int i = 0; i < 3; i++) {
            server2.expect(requestTo(BASE_URL + HttpQuoteService.CREATE_QUOTE_PATH))
                    .andExpect(header(HttpQuoteService.IDEMPOTENCY_KEY_HEADER, notNullValue(String.class)))
                    .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("boom"));
        }
        QuoteRequest req2 = new QuoteRequest();
        req2.setContactId(3L);
        QuoteResult serverError = service2.createQuote(req2);
        assertEquals("FAILED", serverError.status());
        assertTrue(serverError.message().contains("HTTP 500"));
        server2.verify();

        ArgumentCaptor<QuoteRequest> captor2 = ArgumentCaptor.forClass(QuoteRequest.class);
        verify(quoteRequestRepository, times(4)).save(captor2.capture());
        QuoteRequest finalFailed = captor2.getAllValues().get(captor2.getAllValues().size() - 1);
        assertEquals("FAILED", finalFailed.getStatus());
        assertEquals(2, finalFailed.getRetryCount()); // maxAttempts-1 次重试已发生
        assertNotNull(finalFailed.getLastError());
        assertTrue(finalFailed.getLastError().contains("HTTP 500"));
        assertNotNull(finalFailed.getIdempotencyKey());
    }

    @Test
    void retriesHttp500ThenSuccessWithBackoffSuppressed() {
        // 前 maxAttempts-1=2 次 500，第 3 次成功 -> SUCCESS + retryCount==2
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        HttpQuoteService service = new NoBackoffService(quoteRequestRepository, appProperties, restClientBuilder);
        when(quoteRequestRepository.save(any(QuoteRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        server.expect(requestTo(BASE_URL + HttpQuoteService.CREATE_QUOTE_PATH))
                .andExpect(header(HttpQuoteService.IDEMPOTENCY_KEY_HEADER, notNullValue(String.class)))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("boom-1"));
        server.expect(requestTo(BASE_URL + HttpQuoteService.CREATE_QUOTE_PATH))
                .andExpect(header(HttpQuoteService.IDEMPOTENCY_KEY_HEADER, notNullValue(String.class)))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("boom-2"));
        server.expect(requestTo(BASE_URL + HttpQuoteService.CREATE_QUOTE_PATH))
                .andExpect(header(HttpQuoteService.IDEMPOTENCY_KEY_HEADER, notNullValue(String.class)))
                .andRespond(withSuccess(
                        "{\"code\":\"SUCCESS\",\"message\":\"ok\",\"bizRefNo\":\"QT-20260904-002\",\"amount\":19800.00}",
                        MediaType.APPLICATION_JSON));

        QuoteRequest req = new QuoteRequest();
        req.setContactId(5L);
        req.setQuantity(1);

        QuoteResult result = service.createQuote(req);

        assertEquals("QT-20260904-002", result.quoteNo());
        assertEquals("SYNCED", result.status());
        server.verify();

        ArgumentCaptor<QuoteRequest> captor = ArgumentCaptor.forClass(QuoteRequest.class);
        verify(quoteRequestRepository, times(4)).save(captor.capture());
        List<QuoteRequest> records = captor.getAllValues();
        QuoteRequest finalRecord = records.get(records.size() - 1);
        assertEquals("SYNCED", finalRecord.getStatus());
        assertEquals(2, finalRecord.getRetryCount()); // 第 1、2 次失败已计入重试
        assertNotNull(finalRecord.getLastError());     // 保留最近一次失败原因
        assertNotNull(finalRecord.getIdempotencyKey());
    }

    /**
     * 覆写退避为 no-op，保证重试用例不等待真实 100ms+ 指数退避。
     */
    private static final class NoBackoffService extends HttpQuoteService {

        NoBackoffService(QuoteRequestRepository repo, AppProperties props, RestClient.Builder builder) {
            super(repo, props, builder);
        }

        @Override
        protected void doBackoff(int failedAttempt) {
            // no-op
        }
    }
}

package com.example.wechatsales.action;

import com.example.wechatsales.config.AppProperties;
import com.example.wechatsales.domain.QuoteRequest;
import com.example.wechatsales.repository.QuoteRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HttpQuoteService：QuoteService 的真实 HTTP 适配实现。
 *
 * <p>当 {@code app.quote.http.enabled=true} 时注册（MockQuoteService 自动停用），
 * 使用 Spring 自带 {@link RestClient} 向内部报价系统 POST JSON 创建报价，
 * 成功后回填外部业务单号 {@code bizRefNo} 落库；失败时记录 FAILED 状态并返回带原因的结果，
 * 不向上抛异常打断编排流水（编排器侧可按 message 提示人工介入）。</p>
 *
 * <p>请求协议约定（与内部报价系统联调时以对方文档为准，可在本类调整 DTO/路径）：
 * <pre>
 * POST {base-url}/api/v1/quotes
 * headers: Content-Type: application/json, X-API-Key: {app.quote.http.api-key},
 *          Idempotency-Key: {quote_request.idempotency_key}
 * body: {"contactId": 1, "productCode": "CRM-STD", "quantity": 1}
 * 200:  {"code":"SUCCESS","bizRefNo":"QT-20260904-001","amount":19800.00}
 * </pre>
 * 幂等/重试（见 DESIGN.md §10）：创建时生成幂等键并落库，外呼以 Idempotency-Key 头携带；
 * 网络异常/5xx 按 {@code maxAttempts} 指数退避重试（含首次最多 3 次），
 * 连续失败后落库 FAILED + lastError，不向上抛异常打断编排流水。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.quote.http", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class HttpQuoteService implements QuoteService {

    /** 内部报价系统创建报价接口路径（相对 base-url） */
    public static final String CREATE_QUOTE_PATH = "/api/v1/quotes";

    /** 请求鉴权 Header */
    public static final String API_KEY_HEADER = "X-API-Key";

    /** 幂等键请求头（业务系统据此去重） */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /** 重试退避：首次失败后等待 100ms，指数递增封顶 1000ms（测试可覆写为 0 避免等待） */
    static final long BACKOFF_BASE_MILLIS = 100L;
    static final long BACKOFF_MAX_MILLIS = 1000L;

    /** 创建成功的外部系统返回体（字段与业务报价系统约定） */
    public record QuoteApiResponse(String code, String message, String bizRefNo, BigDecimal amount) {
    }

    private final QuoteRequestRepository quoteRequestRepository;
    private final AppProperties appProperties;
    private final RestClient.Builder restClientBuilder;

    @Override
    public QuoteResult createQuote(QuoteRequest request) {
        AppProperties.Http http = requireHttpConfig();

        // 1) 先落库报价请求（CREATED），保证审计完整；幂等键为空则自动生成 UUID
        QuoteRequest record = copyOf(request);
        record.setStatus("CREATED");
        record = quoteRequestRepository.save(record);

        int maxAttempts = record.getMaxAttempts() == null || record.getMaxAttempts() < 1
                ? 3 : record.getMaxAttempts();

        // 2) 最多尝试 maxAttempts 次：网络异常/5xx 指数退避重试；业务失败(code!=SUCCESS)与 4xx 不重试
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                // 3) POST 内部报价系统（携带幂等键头）
                QuoteApiResponse response = restClientBuilder.build().post()
                        .uri(http.getBaseUrl() + CREATE_QUOTE_PATH)
                        .header(API_KEY_HEADER, http.getApiKey())
                        .header(IDEMPOTENCY_KEY_HEADER, record.getIdempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payloadOf(record))
                        .retrieve()
                        .toEntity(QuoteApiResponse.class)
                        .getBody();

                if (response == null || !"SUCCESS".equalsIgnoreCase(response.code())
                        || response.bizRefNo() == null || response.bizRefNo().isBlank()) {
                    // 业务侧明确失败：不重试，避免重复无效调用
                    String reason = response == null ? "报价系统返回空响应"
                            : "报价系统返回失败 code=" + response.code() + " message=" + response.message();
                    markFailed(record, reason);
                    return new QuoteResult(null, null, "FAILED", reason);
                }

                // 4) 成功：回填 bizRefNo/金额/状态 落库（lastError 若之前有可清空，此处保留亦可）
                record.setBizRefNo(response.bizRefNo());
                record.setAmount(response.amount());
                record.setStatus("SYNCED");
                quoteRequestRepository.save(record);

                log.info("[HttpQuoteService] 报价已同步业务系统 contactId={} bizRefNo={} amount={} retryCount={}",
                        record.getContactId(), response.bizRefNo(), response.amount(), record.getRetryCount());
                return new QuoteResult(response.bizRefNo(), response.amount(), "SYNCED",
                        "报价创建成功并已同步业务系统（HTTP）");
            } catch (RestClientResponseException e) {
                // 5xx 视为可重试；4xx/最后一次失败不再重试，落 FAILED
                boolean retryable = e.getStatusCode().value() >= 500 && attempt < maxAttempts;
                String reason = "报价系统 HTTP " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString();
                if (!retryable) {
                    markFailed(record, reason);
                    log.warn("[HttpQuoteService] 调用报价系统失败(HTTP {}，不重试): {}", e.getStatusCode().value(), reason);
                    return new QuoteResult(null, null, "FAILED", reason);
                }
                record.setRetryCount(attempt);
                record.setLastError(reason);
                quoteRequestRepository.save(record);
                log.warn("[HttpQuoteService] 调用报价系统失败(HTTP {})，第 {} 次尝试失败，准备重试: {}",
                        e.getStatusCode().value(), attempt, reason);
                doBackoff(attempt);
            } catch (Exception e) {
                // 网络异常/超时/JSON 解析失败等：可重试；达到上限后落 FAILED
                String reason = "调用报价系统异常: " + e.getMessage();
                if (attempt >= maxAttempts) {
                    markFailed(record, reason);
                    log.warn("[HttpQuoteService] 调用报价系统异常，已尝试 {} 次仍失败（retryCount={}）",
                            attempt, record.getRetryCount());
                    return new QuoteResult(null, null, "FAILED", reason);
                }
                record.setRetryCount(attempt);
                record.setLastError(reason);
                quoteRequestRepository.save(record);
                log.warn("[HttpQuoteService] 调用报价系统异常，第 {} 次尝试失败，准备重试: {}", attempt, reason);
                doBackoff(attempt);
            }
        }

        // 理论不可达（maxAttempts 恒 >= 1），兜底置 FAILED
        String reason = "报价系统不可用，已尝试 " + maxAttempts + " 次仍失败";
        markFailed(record, reason);
        return new QuoteResult(null, null, "FAILED", reason);
    }

    /**
     * 重试前退避。测试可继承 HttpQuoteService 并覆写为 no-op，避免真实等待。
     */
    protected void doBackoff(int failedAttempt) {
        try {
            Thread.sleep(backoffMillis(failedAttempt));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 指数退避毫秒数：100ms * 2^(failedAttempt-1)，封顶 1000ms。
     */
    protected long backoffMillis(int failedAttempt) {
        long exp = BACKOFF_BASE_MILLIS * (1L << (Math.max(failedAttempt, 1) - 1));
        return Math.min(BACKOFF_MAX_MILLIS, exp);
    }

    private AppProperties.Http requireHttpConfig() {
        AppProperties.Http http = appProperties.getQuote().getHttp();
        if (http.getBaseUrl() == null || http.getBaseUrl().isBlank()) {
            throw new IllegalStateException("app.quote.http.base-url 未配置，无法启用 HttpQuoteService");
        }
        return http;
    }

    private QuoteRequest copyOf(QuoteRequest request) {
        QuoteRequest copy = new QuoteRequest();
        copy.setContactId(request.getContactId());
        copy.setProductCode(request.getProductCode());
        copy.setQuantity(request.getQuantity() == null || request.getQuantity() < 1 ? 1 : request.getQuantity());
        copy.setStatus("CREATED");
        copy.touch();
        return copy;
    }

    private Map<String, Object> payloadOf(QuoteRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contactId", request.getContactId());
        payload.put("productCode", request.getProductCode() == null ? "CRM-STD" : request.getProductCode());
        payload.put("quantity", request.getQuantity() == null || request.getQuantity() < 1 ? 1 : request.getQuantity());
        return payload;
    }

    private void markFailed(QuoteRequest record, String reason) {
        record.setStatus("FAILED");
        record.setLastError(reason);
        quoteRequestRepository.save(record);
        log.warn("[HttpQuoteService] 报价记录 {} 置为 FAILED: {}", record.getId(), reason);
    }
}

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
 * headers: Content-Type: application/json, X-API-Key: {app.quote.http.api-key}
 * body: {"contactId": 1, "productCode": "CRM-STD", "quantity": 1}
 * 200:  {"code":"SUCCESS","bizRefNo":"QT-20260904-001","amount":19800.00}
 * </pre>
 * 幂等/重试：M1+ 扩展点（见 DESIGN.md §8.1），当前以本地 {@code quote_request} 记录兜底审计。</p>
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

    /** 创建成功的外部系统返回体（字段与业务报价系统约定） */
    public record QuoteApiResponse(String code, String message, String bizRefNo, BigDecimal amount) {
    }

    private final QuoteRequestRepository quoteRequestRepository;
    private final AppProperties appProperties;
    private final RestClient.Builder restClientBuilder;

    @Override
    public QuoteResult createQuote(QuoteRequest request) {
        AppProperties.Http http = requireHttpConfig();

        // 1) 先落库报价请求（CREATED），保证审计完整；失败也保留一条 FAILED 记录
        QuoteRequest record = copyOf(request);
        record.setStatus("CREATED");
        record = quoteRequestRepository.save(record);

        try {
            // 2) POST 内部报价系统
            QuoteApiResponse response = restClientBuilder.build().post()
                    .uri(http.getBaseUrl() + CREATE_QUOTE_PATH)
                    .header(API_KEY_HEADER, http.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payloadOf(request))
                    .retrieve()
                    .toEntity(QuoteApiResponse.class)
                    .getBody();

            if (response == null || !"SUCCESS".equalsIgnoreCase(response.code())
                    || response.bizRefNo() == null || response.bizRefNo().isBlank()) {
                String reason = response == null ? "报价系统返回空响应"
                        : "报价系统返回失败 code=" + response.code() + " message=" + response.message();
                markFailed(record, reason);
                return new QuoteResult(null, null, "FAILED", reason);
            }

            // 3) 成功：回填 bizRefNo/金额/状态 落库
            record.setBizRefNo(response.bizRefNo());
            record.setAmount(response.amount());
            record.setStatus("SYNCED");
            quoteRequestRepository.save(record);

            log.info("[HttpQuoteService] 报价已同步业务系统 contactId={} bizRefNo={} amount={}",
                    request.getContactId(), response.bizRefNo(), response.amount());
            return new QuoteResult(response.bizRefNo(), response.amount(), "SYNCED",
                    "报价创建成功并已同步业务系统（HTTP）");
        } catch (RestClientResponseException e) {
            // 4xx/5xx：HTTP 失败，保留 FAILED 记录供人工核对
            String reason = "报价系统 HTTP " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString();
            markFailed(record, reason);
            log.warn("[HttpQuoteService] 调用报价系统失败: {}", reason);
            return new QuoteResult(null, null, "FAILED", reason);
        } catch (Exception e) {
            // 网络异常/超时/JSON 解析失败等
            String reason = "调用报价系统异常: " + e.getMessage();
            markFailed(record, reason);
            log.warn("[HttpQuoteService] 调用报价系统异常", e);
            return new QuoteResult(null, null, "FAILED", reason);
        }
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
        quoteRequestRepository.save(record);
        log.warn("[HttpQuoteService] 报价记录 {} 置为 FAILED: {}", record.getId(), reason);
    }
}

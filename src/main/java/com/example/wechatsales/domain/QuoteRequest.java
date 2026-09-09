package com.example.wechatsales.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** 报价请求（SPI：对接已有业务系统/报价中心） */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "quote_request")
public class QuoteRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contact_id", nullable = false)
    private Long contactId;

    @Column(name = "product_code", length = 64)
    private String productCode;

    @Column(nullable = false)
    private Integer quantity = 1;

    @Column(precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "biz_ref_no", length = 128)
    private String bizRefNo;

    /** 幂等键（UUID 或业务键），HTTP 调用以 Idempotency-Key 头携带，供业务系统去重 */
    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    /** 已重试次数（不含首次尝试） */
    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    /** 最近一次失败原因 */
    @Column(name = "last_error", length = 1000)
    private String lastError;

    /** 允许的最大尝试次数（含首次，默认 3） */
    @Column(name = "max_attempts", nullable = false)
    private Integer maxAttempts = 3;

    @Column(nullable = false, length = 32)
    private String status = "CREATED";

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 生成幂等键：为空时用 UUID（落库/外呼前调用，保证幂等头非空） */
    public void ensureIdempotencyKey() {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            idempotencyKey = UUID.randomUUID().toString();
        }
    }

    public void touch() {
        ensureIdempotencyKey();
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}

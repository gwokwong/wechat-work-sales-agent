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

    @Column(nullable = false, length = 32)
    private String status = "CREATED";

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public void touch() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}

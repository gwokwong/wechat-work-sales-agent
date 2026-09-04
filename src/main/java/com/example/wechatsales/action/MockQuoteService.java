package com.example.wechatsales.action;

import com.example.wechatsales.domain.QuoteRequest;
import com.example.wechatsales.repository.QuoteRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * MockQuoteService：报价 SPI 的演示实现。
 * 不真实对接业务系统，生成带时间戳的报价单号与测算金额并落库。
 * M1 阶段将替换为调用内部报价中心/CRM 的真实实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MockQuoteService implements QuoteService {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final QuoteRequestRepository quoteRequestRepository;

    @Override
    public QuoteResult createQuote(QuoteRequest request) {
        int qty = request.getQuantity() == null || request.getQuantity() < 1 ? 1 : request.getQuantity();
        BigDecimal unit = new BigDecimal("19800");
        BigDecimal amount = unit.multiply(BigDecimal.valueOf(qty));

        QuoteRequest saved = new QuoteRequest();
        saved.setContactId(request.getContactId());
        saved.setProductCode(request.getProductCode() == null ? "CRM-STD" : request.getProductCode());
        saved.setQuantity(qty);
        saved.setAmount(amount);
        saved.setStatus("CREATED");
        saved.touch();

        String quoteNo = "MOCK-QT-" + LocalDateTime.now().format(NO_FMT);
        saved.setBizRefNo(quoteNo);
        quoteRequestRepository.save(saved);

        log.info("[MockQuoteService] 已创建报价单 {} 金额={} 客户={}", quoteNo, amount, request.getContactId());
        return QuoteResult.ok(quoteNo, amount);
    }
}

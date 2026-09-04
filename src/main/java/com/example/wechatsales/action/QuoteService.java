package com.example.wechatsales.action;

import com.example.wechatsales.domain.QuoteRequest;

/**
 * 报价 SPI：对接已有业务系统（报价中心/CRM/ERP）的统一出入口。
 *
 * <p>真实接入时实现本接口，把 {@link QuoteRequest} 同步/异步推送到内部报价系统，
 * 并回填 bizRefNo（外部业务单号）。MockQuoteService 为演示实现。</p>
 */
public interface QuoteService {

    QuoteResult createQuote(QuoteRequest request);
}

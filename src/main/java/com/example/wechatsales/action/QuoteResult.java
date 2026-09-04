package com.example.wechatsales.action;

import java.math.BigDecimal;

/** 报价结果 */
public record QuoteResult(String quoteNo, BigDecimal amount, String status, String message) {

    public static QuoteResult ok(String quoteNo, BigDecimal amount) {
        return new QuoteResult(quoteNo, amount, "SYNCED", "报价创建成功并已同步业务系统（Mock）");
    }
}

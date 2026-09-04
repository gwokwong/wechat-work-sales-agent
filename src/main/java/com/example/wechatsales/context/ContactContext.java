package com.example.wechatsales.context;

import com.example.wechatsales.domain.Contact;
import com.example.wechatsales.domain.CustomerProfile;
import com.example.wechatsales.domain.Deal;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 客户上下文快照：短期最近N轮消息 + 客户画像 + 长期阶段摘要 + 商机。
 * 供策略选择与 LLM 话术生成使用。
 */
@Data
@Builder
public class ContactContext {

    private Contact contact;

    private CustomerProfile profile;

    private Deal deal;

    /** 最近 N 轮客户来消息（新→旧，短期记忆） */
    private List<String> recentCustomerMessages;

    /** 最近对话轮次上限（默认 10） */
    private int recentLimit;

    public String customerName() {
        return contact == null ? "客户" : contact.getName();
    }
}

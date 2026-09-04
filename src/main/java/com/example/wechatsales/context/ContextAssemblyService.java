package com.example.wechatsales.context;

import com.example.wechatsales.domain.Contact;
import com.example.wechatsales.domain.Deal;
import com.example.wechatsales.domain.SalesStage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 上下文组装服务：将存储中的客户数据组装为可直接投喂策略/LLM 的上下文快照。
 */
@Service
@RequiredArgsConstructor
public class ContextAssemblyService {

    private final ContextStore contextStore;

    /** 按 external_userid 组装完整上下文 */
    public ContactContext assembleByExternalId(String externalUserId) {
        Contact contact = contextStore.getOrCreateContact(externalUserId);
        return assemble(contact);
    }

    public ContactContext assemble(Long contactId) {
        Contact contact = contextStore.getOrCreateContactByInternalId(contactId);
        return assemble(contact);
    }

    public ContactContext assemble(Contact contact) {
        Optional<Deal> dealOpt = contextStore.findDeal(contact.getId());
        return ContactContext.builder()
                .contact(contact)
                .profile(contextStore.getOrCreateProfile(contact.getId()))
                .deal(dealOpt.orElse(null))
                .recentCustomerMessages(contextStore.recentCustomerMessages(contact.getId(), ContextStore.DEFAULT_RECENT_LIMIT))
                .recentLimit(ContextStore.DEFAULT_RECENT_LIMIT)
                .build();
    }

    public Contact requireContact(Long contactId) {
        return contextStore.getOrCreateContactByInternalId(contactId);
    }

    /** 按 external_userid 取/建联系人（通道首次来消息时自动建档） */
    public Contact requireContactByExternalId(String externalUserId) {
        return contextStore.getOrCreateContact(externalUserId);
    }

    /** 渲染为便于 LLM/策略阅读的文本摘要 */
    public String renderSummary(ContactContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("【客户】").append(ctx.customerName());
        if (ctx.getContact().getCompany() != null && !ctx.getContact().getCompany().isBlank()) {
            sb.append("（").append(ctx.getContact().getCompany()).append("）");
        }
        sb.append("\n");

        if (ctx.getProfile() != null) {
            sb.append("【长期画像】阶段摘要: ").append(nvl(ctx.getProfile().getStageSummary()))
                    .append(" | 需求: ").append(nvl(ctx.getProfile().getNeedsSummary()))
                    .append(" | 偏好: ").append(nvl(ctx.getProfile().getPreferredTopics()))
                    .append("\n");
        }
        if (ctx.getDeal() != null) {
            sb.append("【商机】").append(ctx.getDeal().getDealName())
                    .append(" | 当前阶段: ").append(ctx.getDeal().getStage().name())
                    .append(" (").append(ctx.getDeal().getStage().displayName()).append(")\n");
        }
        sb.append("【最近对话】").append(ctx.getRecentCustomerMessages().size()).append(" 轮:\n");
        for (String msg : ctx.getRecentCustomerMessages()) {
            sb.append("  - 客户: ").append(truncate(msg)).append("\n");
        }
        return sb.toString();
    }

    public SalesStage currentStageOf(ContactContext ctx) {
        return ctx.getDeal() == null ? SalesStage.LEAD_INITIAL : ctx.getDeal().getStage();
    }

    private String nvl(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 120 ? s : s.substring(0, 120) + "...";
    }
}

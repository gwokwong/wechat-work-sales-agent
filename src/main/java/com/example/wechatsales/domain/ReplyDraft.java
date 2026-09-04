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

import java.time.LocalDateTime;

/**
 * 待审批话术草稿：Agent 生成 → 合规校验 → 人工闸门 → 发送。
 * status: PENDING / APPROVED / REJECTED / SENT / BLOCKED
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "reply_draft")
public class ReplyDraft {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_BLOCKED = "BLOCKED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contact_id", nullable = false)
    private Long contactId;

    @Column(name = "source_msg_id", length = 128)
    private String sourceMsgId;

    @Column(nullable = false, length = 32)
    private String stage;

    @Column(name = "strategy_name", nullable = false, length = 128)
    private String strategyName;

    @Column(name = "action_type", nullable = false, length = 32)
    private String actionType = "SEND_TEXT";

    @Column(nullable = false, length = 2000)
    private String content;

    @Column(length = 500)
    private String reason;

    @Column(nullable = false, length = 32)
    private String status = STATUS_PENDING;

    @Column(name = "blocked_reason", length = 500)
    private String blockedReason;

    @Column(name = "quote_reference", length = 128)
    private String quoteReference;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    public void touch() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    /** 人工通过（不发送，仅置 APPROVED；真正发送由 DraftApprovalService 调 OutboundSender 完成） */
    public void approve() {
        this.status = STATUS_APPROVED;
        this.decidedAt = LocalDateTime.now();
    }

    public void reject() {
        this.status = STATUS_REJECTED;
        this.decidedAt = LocalDateTime.now();
    }

    public void markSent() {
        this.status = STATUS_SENT;
        this.sentAt = LocalDateTime.now();
    }

    public void block(String reason) {
        this.status = STATUS_BLOCKED;
        this.blockedReason = reason;
        this.decidedAt = LocalDateTime.now();
    }
}

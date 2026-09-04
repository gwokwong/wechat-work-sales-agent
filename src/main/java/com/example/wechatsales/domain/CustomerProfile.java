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

/** 客户画像（长期阶段摘要 + 需求 + 偏好 + 风险，随对话持续沉淀） */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "customer_profile")
public class CustomerProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contact_id", nullable = false, unique = true)
    private Long contactId;

    @Column(name = "stage_summary", length = 2000)
    private String stageSummary;

    @Column(name = "needs_summary", length = 2000)
    private String needsSummary;

    @Column(name = "preferred_topics", length = 1000)
    private String preferredTopics;

    @Column(name = "risk_notes", length = 1000)
    private String riskNotes;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public void touch() {
        updatedAt = LocalDateTime.now();
    }

    /** 向需求摘要追加一条记录（简单截断保护） */
    public void appendNeeds(String text) {
        needsSummary = merge(needsSummary, text, 2000);
    }

    /** 向偏好话题追加 */
    public void appendTopic(String text) {
        preferredTopics = merge(preferredTopics, text, 1000);
    }

    private static String merge(String current, String add, int maxLen) {
        if (add == null || add.isBlank()) {
            return current;
        }
        String base = current == null ? "" : current;
        String merged = base.isBlank() ? add : base + "；" + add;
        if (merged.length() > maxLen) {
            merged = merged.substring(merged.length() - maxLen);
        }
        return merged;
    }
}

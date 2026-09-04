package com.example.wechatsales.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 商机（与销售阶段状态机绑定） */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "deal")
public class Deal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contact_id", nullable = false)
    private Long contactId;

    @Column(name = "deal_name", nullable = false, length = 255)
    private String dealName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SalesStage stage = SalesStage.LEAD_INITIAL;

    @Column(precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(length = 1000)
    private String description;

    @Column(name = "opened_at")
    private LocalDateTime openedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "closed_reason", length = 500)
    private String closedReason;

    public void touch() {
        LocalDateTime now = LocalDateTime.now();
        if (openedAt == null) {
            openedAt = now;
        }
        updatedAt = now;
    }

    public boolean isTerminal() {
        return stage != null && stage.isTerminal();
    }
}

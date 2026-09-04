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

/** 动作日志（审计流：草稿、审批、报价、外发、阶段跃迁等） */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "action_log")
public class ActionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contact_id")
    private Long contactId;

    @Column(name = "action_type", nullable = false, length = 64)
    private String actionType;

    @Column(length = 2000)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public void touch() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}

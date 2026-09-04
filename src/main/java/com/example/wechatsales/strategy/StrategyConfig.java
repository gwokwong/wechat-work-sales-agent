package com.example.wechatsales.strategy;

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

/** 回复策略配置实体（表 strategy_config），与 resources/strategies/*.json 同构 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "strategy_config")
public class StrategyConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String stage;

    @Column(name = "rule_name", nullable = false, length = 128)
    private String ruleName;

    /** 触发关键词，| 分隔；空 = 该阶段兜底策略 */
    @Column(name = "trigger_keywords", length = 1000)
    private String triggerKeywords;

    @Column(name = "action_type", nullable = false, length = 32)
    private String actionType = "SEND_TEXT";

    @Column(name = "template_content", nullable = false, length = 2000)
    private String templateContent;

    @Column(nullable = false)
    private Integer priority = 100;

    @Column(nullable = false)
    private Boolean enabled = Boolean.TRUE;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public void touch() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}

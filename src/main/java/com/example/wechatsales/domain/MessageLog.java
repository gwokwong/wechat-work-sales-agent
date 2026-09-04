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

/** 消息流水（IN=客户消息；OUT=Agent/人工外发），msgId 唯一用于幂等去重 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "message_log")
public class MessageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "msg_id", nullable = false, unique = true, length = 128)
    private String msgId;

    @Column(name = "contact_id", nullable = false)
    private Long contactId;

    @Column(nullable = false, length = 16)
    private String direction;

    @Column(name = "sender_type", nullable = false, length = 16)
    private String senderType;

    @Column(nullable = false, length = 4000)
    private String content;

    @Column(name = "msg_type", length = 32)
    private String msgType = "text";

    @Column(name = "channel_type", length = 32)
    private String channelType = "wecom";

    @Column(nullable = false)
    private Boolean processed = Boolean.TRUE;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public void touch() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}

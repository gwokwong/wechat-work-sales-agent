package com.example.wechatsales.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 内存中的消息对象（通道与编排器之间传递），落库使用 {@link MessageLog} */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Message {

    /** 消息 ID（企微 msgId；Mock 通道生成 mock-xxx） */
    private String msgId;

    /** 客户企微 external_userid */
    private String contactExternalId;

    /** 方向 IN/OUT */
    private Direction direction;

    /** CUSTOMER / AGENT / SYSTEM */
    private String senderType;

    /** 文本内容 */
    private String content;

    /** 通道类型 mock / wecom */
    private String channelType;

    private LocalDateTime receivedAt;

    public static Message inbound(String msgId, String contactExternalId, String content, String channelType) {
        return Message.builder()
                .msgId(msgId)
                .contactExternalId(contactExternalId)
                .direction(Direction.IN)
                .senderType("CUSTOMER")
                .content(content)
                .channelType(channelType)
                .receivedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 员工在企微侧的发言（会话存档映射为 OUT）：归属到对应外部客户，
     * 仅用于补充对话上下文与审计，不触发策略回复。
     */
    public static Message staffOutbound(String msgId, String contactExternalId, String content, String channelType) {
        return Message.builder()
                .msgId(msgId)
                .contactExternalId(contactExternalId)
                .direction(Direction.OUT)
                .senderType("STAFF")
                .content(content)
                .channelType(channelType)
                .receivedAt(LocalDateTime.now())
                .build();
    }
}

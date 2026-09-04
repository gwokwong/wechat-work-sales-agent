package com.example.wechatsales.channel;

/**
 * 企微通道适配器抽象：真实实现（WeComChannel）与 Mock 实现（MockChannel）
 * 均实现本接口，由 app.channel.active 决定 OutboundSender 使用哪个。
 */
public interface Channel {

    /** 通道标识：mock / wecom */
    String channelType();

    /**
     * 向客户发送一条消息（文本）。
     *
     * @param message 待发送消息
     * @return 发送结果（messageId 供落库与审计；失败时 success=false + error）
     */
    SendResult send(OutboundMessage message);
}

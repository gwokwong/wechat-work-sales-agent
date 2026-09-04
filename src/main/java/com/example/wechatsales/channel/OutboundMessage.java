package com.example.wechatsales.channel;

/** 外发消息 */
public record OutboundMessage(String contactExternalId, String content) {
}

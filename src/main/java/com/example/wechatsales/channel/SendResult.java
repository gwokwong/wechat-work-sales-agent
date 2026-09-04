package com.example.wechatsales.channel;

/** 发送结果 */
public record SendResult(boolean success, String messageId, String error) {

    public static SendResult ok(String messageId) {
        return new SendResult(true, messageId, null);
    }

    public static SendResult fail(String error) {
        return new SendResult(false, null, error);
    }
}

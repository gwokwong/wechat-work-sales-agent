package com.example.wechatsales.channel;

import com.example.wechatsales.domain.Message;

/** 消息监听器 */
public interface MessageListener {

    void onMessage(Message message);
}

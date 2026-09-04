package com.example.wechatsales.channel;

import com.example.wechatsales.domain.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 消息总线：通道（Channel）把收到的客户消息 publish 到这里，
 * 监听器（如销售编排器 SalesAgentOrchestrator）订阅消费。
 *
 * <p>真实企微接入时，回调接口只负责"5 秒内 ack + 投递到总线"，
 * 总线内部监听器可异步处理，避免阻塞企微回调。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageBus {

    private final List<MessageListener> listeners = new CopyOnWriteArrayList<>();

    public void register(MessageListener listener) {
        listeners.add(listener);
        log.info("[MessageBus] 注册消息监听器: {}", listener.getClass().getSimpleName());
    }

    /** 发布一条消息，任一监听器异常不影响其他监听器 */
    public void publish(Message message) {
        log.info("[MessageBus] 收到消息 msgId={} from={} content={}",
                message.getMsgId(), message.getContactExternalId(), truncate(message.getContent()));
        for (MessageListener listener : listeners) {
            try {
                listener.onMessage(message);
            } catch (Exception e) {
                log.error("[MessageBus] 监听器 {} 处理消息失败: {}",
                        listener.getClass().getSimpleName(), e.getMessage(), e);
            }
        }
    }

    private String truncate(String s) {
        if (s == null || s.length() <= 80) {
            return s;
        }
        return s.substring(0, 80) + "...";
    }
}

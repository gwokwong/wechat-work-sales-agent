package com.example.wechatsales.channel;

import com.example.wechatsales.config.AppProperties;
import com.example.wechatsales.domain.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MockChannel：演示通道。
 * <ul>
 *   <li>实现 {@link Channel}，用于外发演示消息（OutboundSender 会调用）；</li>
 *   <li>通过 Spring {@code @Scheduled} 定时器按 app.mock.interval-ms 每分钟自动
 *       注入一条"模拟客户消息"到 MessageBus，驱动 Agent 自动处理流水线；</li>
 *   <li>剧本用尽后自动停发，避免无限刷屏；也支持 REST 手动触发 {@link #injectNow()} 或 {@link #inject(String, String)}。</li>
 * </ul>
 */
@Slf4j
@Component("mockChannel")
@RequiredArgsConstructor
public class MockChannel implements Channel {

    private final MessageBus messageBus;
    private final AppProperties appProperties;

    private final AtomicInteger index = new AtomicInteger(0);
    private final AtomicBoolean scriptDone = new AtomicBoolean(false);

    @Override
    public String channelType() {
        return "mock";
    }

    @Override
    public SendResult send(OutboundMessage message) {
        // Mock 通道不真正外呼，直接模拟成功返回，消息内容已由 OutboundSender 落库审计
        log.info("[MockChannel] 模拟外发 -> {} : {}", message.contactExternalId(), message.content());
        return SendResult.ok("mock-out-" + UUID.randomUUID());
    }

    /** 定时器：演示自动流水线入口（默认每分钟一条，可配置） */
    @Scheduled(initialDelayString = "${app.mock.first-delay-ms:15000}",
            fixedDelayString = "${app.mock.interval-ms:60000}")
    public void scheduledInject() {
        if (!appProperties.getMock().isEnabled()) {
            return;
        }
        injectNextPreset();
    }

    /** 手动触发：注入下一条预置剧本消息 */
    public synchronized boolean injectNextPreset() {
        if (scriptDone.get()) {
            log.info("[MockChannel] 剧本已播完，不再注入");
            return false;
        }
        List<String> messages = appProperties.getMock().getMessages();
        int i = index.getAndIncrement();
        if (i >= messages.size()) {
            scriptDone.set(true);
            log.info("[MockChannel] 演示剧本全部播完，共 {} 条，定时流水线停止", messages.size());
            return false;
        }
        String line = messages.get(i);
        String contactExternalId = line.contains("|") ? line.split("\\|", 2)[0].trim() : appProperties.getMock().getCustomerAId();
        String content = line.contains("|") ? line.split("\\|", 2)[1].trim() : line.trim();
        publishInbound(contactExternalId, content);
        return true;
    }

    /** REST 手动注入：任意客户任意内容 */
    public void inject(String contactExternalId, String content) {
        publishInbound(contactExternalId, content);
    }

    private void publishInbound(String contactExternalId, String content) {
        Message message = Message.inbound(
                "mock-" + UUID.randomUUID(),
                contactExternalId,
                content,
                channelType());
        messageBus.publish(message);
    }

    /** 供 AdminController 展示剧本剩余数量 */
    public int remainingScripts() {
        int total = appProperties.getMock().getMessages().size();
        int used = Math.min(index.get(), total);
        return Math.max(0, total - used);
    }

    public List<String> currentScripts() {
        return new ArrayList<>(appProperties.getMock().getMessages());
    }
}

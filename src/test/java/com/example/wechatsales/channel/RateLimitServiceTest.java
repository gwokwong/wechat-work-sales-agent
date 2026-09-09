package com.example.wechatsales.channel;

import com.example.wechatsales.action.ActionLogger;
import com.example.wechatsales.config.AppProperties;
import com.example.wechatsales.domain.Contact;
import com.example.wechatsales.domain.MessageLog;
import com.example.wechatsales.repository.ContactRepository;
import com.example.wechatsales.repository.MessageLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 客户级令牌桶限流单测：同客户超容量拒绝 / 不同客户隔离 / 开关关闭放行 /
 * OutboundSender 接线（开启限流时拦截不落库、关闭时不限流）。
 */
@ExtendWith(MockitoExtension.class)
class RateLimitServiceTest {

    @Mock
    private ContactRepository contactRepository;
    @Mock
    private MessageLogRepository messageLogRepository;
    @Mock
    private ActionLogger actionLogger;
    @Mock
    private Channel mockChannel;

    // ---- RateLimitService：同一客户超过容量后返回 false ----

    @Test
    void sameCustomerExceedsCapacityRejected() {
        RateLimitService service = rateLimitService(true, 2, 0);

        assertTrue(service.tryAcquire("wxid_customer_a"));
        assertTrue(service.tryAcquire("wxid_customer_a"));
        assertFalse(service.tryAcquire("wxid_customer_a"));
    }

    // ---- RateLimitService：不同 externalUserId 互不影响 ----

    @Test
    void differentCustomersAreIsolated() {
        RateLimitService service = rateLimitService(true, 1, 0);

        assertTrue(service.tryAcquire("wxid_customer_a"));
        assertFalse(service.tryAcquire("wxid_customer_a"));
        // 客户 B 独立满桶，不受 A 消耗影响
        assertTrue(service.tryAcquire("wxid_customer_b"));
    }

    // ---- RateLimitService：开关关闭时恒放行 ----

    @Test
    void disabledAlwaysAllows() {
        RateLimitService service = rateLimitService(false, 1, 0);

        for (int i = 0; i < 5; i++) {
            assertTrue(service.tryAcquire("wxid_customer_a"));
        }
    }

    // ---- OutboundSender：开启限流时超限拦截，不发送、不落库、写审计 ----

    @Test
    void outboundSenderBlocksWhenRateLimited() {
        AppProperties props = appProperties(true, 1, 0);
        RateLimitService rateService = new RateLimitService(props);
        OutboundSender sender = new OutboundSender(Map.of("mock", mockChannel),
                contactRepository, messageLogRepository, actionLogger, props, rateService);

        Contact contact = contact(1L, "wxid_customer_a");
        when(contactRepository.findById(1L)).thenReturn(Optional.of(contact));
        when(mockChannel.channelType()).thenReturn("mock");
        when(mockChannel.send(any(OutboundMessage.class))).thenReturn(SendResult.ok("mock-msg-1"));

        // 第一次在桶容量内：正常发送并落库
        SendResult first = sender.sendByContactId(1L, "第一次消息");
        assertTrue(first.success());
        verify(mockChannel, times(1)).send(any(OutboundMessage.class));
        verify(messageLogRepository, times(1)).save(any(MessageLog.class));

        // 第二次超过容量：限流拦截
        SendResult second = sender.sendByContactId(1L, "第二次消息");
        assertFalse(second.success());
        assertTrue(second.error().contains("频率超限"));
        // 未再调通道、未再落库，写限流审计
        verify(mockChannel, times(1)).send(any(OutboundMessage.class));
        verify(messageLogRepository, times(1)).save(any(MessageLog.class));
        verify(actionLogger).log(eq(1L), eq("MESSAGE_SEND_RATE_LIMITED"), contains("externalUserId=wxid_customer_a"));
    }

    // ---- OutboundSender：开关关闭时不限流 ----

    @Test
    void outboundSenderNotLimitedWhenDisabled() {
        AppProperties props = appProperties(false, 1, 0);
        RateLimitService rateService = new RateLimitService(props);
        OutboundSender sender = new OutboundSender(Map.of("mock", mockChannel),
                contactRepository, messageLogRepository, actionLogger, props, rateService);

        Contact contact = contact(2L, "wxid_customer_b");
        when(contactRepository.findById(2L)).thenReturn(Optional.of(contact));
        when(mockChannel.channelType()).thenReturn("mock");
        when(mockChannel.send(any(OutboundMessage.class))).thenReturn(SendResult.ok("mock-msg-2"));

        assertTrue(sender.sendByContactId(2L, "消息一").success());
        assertTrue(sender.sendByContactId(2L, "消息二").success());
        verify(mockChannel, times(2)).send(any(OutboundMessage.class));
        verify(messageLogRepository, times(2)).save(any(MessageLog.class));
    }

    private RateLimitService rateLimitService(boolean enabled, int capacity, double perSecond) {
        return new RateLimitService(appProperties(enabled, capacity, perSecond));
    }

    private AppProperties appProperties(boolean enabled, int capacity, double perSecond) {
        AppProperties props = new AppProperties();
        props.getChannel().setActive("mock");
        props.getWecom().setRateLimitEnabled(enabled);
        props.getWecom().setRateLimitCapacity(capacity);
        props.getWecom().setRateLimitPerSecond(perSecond);
        return props;
    }

    private Contact contact(Long id, String externalUserId) {
        Contact c = new Contact();
        c.setId(id);
        c.setExternalUserId(externalUserId);
        c.setName("客户" + id);
        return c;
    }
}

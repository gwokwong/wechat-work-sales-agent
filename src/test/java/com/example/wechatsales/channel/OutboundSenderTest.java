package com.example.wechatsales.channel;

import com.example.wechatsales.action.ActionLogger;
import com.example.wechatsales.config.AppProperties;
import com.example.wechatsales.domain.Contact;
import com.example.wechatsales.domain.MessageLog;
import com.example.wechatsales.repository.ContactRepository;
import com.example.wechatsales.repository.MessageLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 外发长文本分片单测：短文本不分片单条发送；超长文本按 [i/n] 编号逐片发送并逐片落库；
 * 任一片限流失败停止后续；splitContent 边界（恰等 max / 换行优先 / 无换行硬切）。
 */
@ExtendWith(MockitoExtension.class)
class OutboundSenderTest {

    @Mock
    private ContactRepository contactRepository;
    @Mock
    private MessageLogRepository messageLogRepository;
    @Mock
    private ActionLogger actionLogger;
    @Mock
    private Channel mockChannel;

    // ---- 短文本：不分片，只发一条、只落库一条 OUT（不带编号前缀）----

    @Test
    void shortMessageNotSplitSendsOnceAndSavesSingleOut() {
        OutboundSender sender = sender(appProperties(false, 0, 0));
        when(contactRepository.findById(1L)).thenReturn(Optional.of(contact(1L, "wxid_split_short")));
        when(mockChannel.channelType()).thenReturn("mock");
        when(mockChannel.send(any(OutboundMessage.class))).thenReturn(SendResult.ok("mock-short"));

        SendResult result = sender.sendByContactId(1L, "这是一条短消息");
        assertTrue(result.success());

        ArgumentCaptor<OutboundMessage> msgCaptor = ArgumentCaptor.forClass(OutboundMessage.class);
        verify(mockChannel, times(1)).send(msgCaptor.capture());
        assertEquals("这是一条短消息", msgCaptor.getValue().content());

        ArgumentCaptor<MessageLog> logCaptor = ArgumentCaptor.forClass(MessageLog.class);
        verify(messageLogRepository, times(1)).save(logCaptor.capture());
        assertEquals("这是一条短消息", logCaptor.getValue().getContent());
    }

    // ---- 长文本（5000 字，max=2048）：应分 3 片、带 [1/3]~[3/3] 前缀、顺序发送且每片均落库 ----

    @Test
    void longMessageSplitIntoSegmentsWithPrefixesInOrder() {
        OutboundSender sender = sender(appProperties(false, 0, 0));
        when(contactRepository.findById(1L)).thenReturn(Optional.of(contact(1L, "wxid_split_long")));
        when(mockChannel.channelType()).thenReturn("mock");
        AtomicInteger seq = new AtomicInteger();
        when(mockChannel.send(any(OutboundMessage.class)))
                .thenAnswer(inv -> SendResult.ok("mock-split-" + seq.incrementAndGet()));

        String content = "a".repeat(5000);
        SendResult result = sender.sendByContactId(1L, content);
        assertTrue(result.success());

        // 3 片，前缀与顺序正确
        ArgumentCaptor<OutboundMessage> msgCaptor = ArgumentCaptor.forClass(OutboundMessage.class);
        verify(mockChannel, times(3)).send(msgCaptor.capture());
        List<String> sent = msgCaptor.getAllValues().stream().map(OutboundMessage::content).toList();
        assertTrue(sent.get(0).startsWith("[1/3] "));
        assertTrue(sent.get(1).startsWith("[2/3] "));
        assertTrue(sent.get(2).startsWith("[3/3] "));
        // 加编号前缀后仍不超单条上限（2048）
        for (String chunk : sent) {
            assertTrue(chunk.length() <= 2048, "分片超长: " + chunk.length());
        }
        // 去掉前缀按顺序拼接 = 原文，顺序无丢失/重复
        StringBuilder rebuilt = new StringBuilder();
        for (String chunk : sent) {
            rebuilt.append(chunk.replaceFirst("^\\[\\d+/\\d+\\] ", ""));
        }
        assertEquals(content, rebuilt.toString());

        // 每片均落库 OUT，内容与通道实际发送一致（顺序一致）
        ArgumentCaptor<MessageLog> logCaptor = ArgumentCaptor.forClass(MessageLog.class);
        verify(messageLogRepository, times(3)).save(logCaptor.capture());
        List<String> saved = logCaptor.getAllValues().stream().map(MessageLog::getContent).toList();
        assertEquals(sent, saved);
    }

    // ---- 分片中任一片限流失败：停止后续分片并返回该失败 SendResult ----

    @Test
    void rateLimitStopsRemainingSegments() {
        // 桶容量 1：第一片成功消耗令牌后，第二片触发限流拦截
        OutboundSender sender = sender(appProperties(true, 1, 0));
        when(contactRepository.findById(1L)).thenReturn(Optional.of(contact(1L, "wxid_split_limited")));
        when(mockChannel.channelType()).thenReturn("mock");
        when(mockChannel.send(any(OutboundMessage.class))).thenReturn(SendResult.ok("mock-limited"));

        SendResult result = sender.sendByContactId(1L, "b".repeat(5000));
        assertFalse(result.success());
        assertTrue(result.error().contains("频率超限"));

        // 只发成了第 1 片：通道只被调一次、只落库一条；后续分片被限流拦截
        verify(mockChannel, times(1)).send(any(OutboundMessage.class));
        verify(messageLogRepository, times(1)).save(any(MessageLog.class));
        verify(actionLogger).log(eq(1L), eq("MESSAGE_SEND_RATE_LIMITED"), contains("externalUserId=wxid_split_limited"));
    }

    // ---- splitContent 边界：恰等 max 不分片 / 换行边界优先 / 无换行硬切 ----

    @Test
    void splitContentBoundaryCases() {
        // 恰等 max：不分片
        List<String> exact = OutboundSender.splitContent("1234567890", 10);
        assertEquals(List.of("1234567890"), exact);

        // 超长但窗口内有换行：优先在换行处（\n 行尾）切，不硬切在句中
        // "AAAA\nBBBBCCCC" max=8：窗口 [0,8) 内最后一个换行在 index4，切为 "AAAA\n" + "BBBBCCCC"
        List<String> newlineBoundary = OutboundSender.splitContent("AAAA\nBBBBCCCC", 8);
        assertEquals(List.of("AAAA\n", "BBBBCCCC"), newlineBoundary);

        // 无任何换行：按字符硬切
        List<String> hardCut = OutboundSender.splitContent("ABCDEFGHIJK", 4);
        assertEquals(List.of("ABCD", "EFGH", "IJK"), hardCut);

        // 空/null 输入：返回空列表
        assertTrue(OutboundSender.splitContent("", 4).isEmpty());
        assertTrue(OutboundSender.splitContent(null, 4).isEmpty());
    }

    // ---- helpers ----

    private OutboundSender sender(AppProperties props) {
        return new OutboundSender(Map.of("mock", mockChannel),
                contactRepository, messageLogRepository, actionLogger, props, new RateLimitService(props));
    }

    private AppProperties appProperties(boolean rateLimitEnabled, int capacity, double perSecond) {
        AppProperties props = new AppProperties();
        props.getChannel().setActive("mock");
        props.getWecom().setRateLimitEnabled(rateLimitEnabled);
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

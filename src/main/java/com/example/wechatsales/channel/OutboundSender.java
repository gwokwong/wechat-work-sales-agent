package com.example.wechatsales.channel;

import com.example.wechatsales.action.ActionLogger;
import com.example.wechatsales.config.AppProperties;
import com.example.wechatsales.domain.Contact;
import com.example.wechatsales.domain.Direction;
import com.example.wechatsales.domain.MessageLog;
import com.example.wechatsales.exception.NotFoundException;
import com.example.wechatsales.repository.ContactRepository;
import com.example.wechatsales.repository.MessageLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 外发发送器：根据 app.channel.active 选择 mock / wecom 通道，
 * 统一负责：长文本分片 → 限流检查 → 选通道 → 发送 → 落库 OUT 消息 → 写动作审计。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboundSender {

    /** 配置异常（≤0）时的分片兜底上限，与 AppProperties.Wecom.maxOutboundLength 默认值一致 */
    static final int DEFAULT_MAX_OUTBOUND_LENGTH = 2048;

    private final Map<String, Channel> channels;
    private final ContactRepository contactRepository;
    private final MessageLogRepository messageLogRepository;
    private final ActionLogger actionLogger;
    private final AppProperties appProperties;
    private final RateLimitService rateLimitService;

    /** 按 contactId 向客户发送文本（人工确认/手动发送共用入口） */
    public SendResult sendByContactId(Long contactId, String content) {
        Contact contact = contactRepository.findById(contactId)
                .orElseThrow(() -> new NotFoundException("联系人不存在 contactId=" + contactId));
        return doSend(contact, content);
    }

    /** 按 external_userid 向客户发送文本 */
    public SendResult sendByExternalUserId(String externalUserId, String content) {
        Contact contact = contactRepository.findByExternalUserId(externalUserId)
                .orElseThrow(() -> new NotFoundException("联系人不存在 externalUserId=" + externalUserId));
        return doSend(contact, content);
    }

    /**
     * 外发编排：单条不超过 maxOutboundLength 时原逻辑直发；
     * 超长时按段落/换行边界分片，每片加 "[i/n] " 编号前缀后逐片走完整链路
     * （限流 → 通道发送 → 落库 OUT → 审计），任一片失败即停止后续分片并返回该失败结果。
     */
    private SendResult doSend(Contact contact, String content) {
        if (content == null || content.isEmpty()) {
            return SendResult.fail("外发内容为空");
        }
        int maxLength = Math.max(appProperties.getWecom().getMaxOutboundLength(),
                DEFAULT_MAX_OUTBOUND_LENGTH);
        List<String> parts = splitContent(content, maxLength);
        if (parts.size() == 1) {
            // 单片：原链路直发，不加编号前缀，保持既有语义（messageId 直接透传）
            return sendText(contact, parts.get(0));
        }

        // 编号前缀 "[n/m] " 占字符预算：按总片数扣除后重切，保证加前缀后仍不超单条上限
        int prefixLength = ("[" + parts.size() + "/" + parts.size() + "] ").length();
        if (maxLength - prefixLength >= 1) {
            parts = splitContent(content, maxLength - prefixLength);
        }
        for (int i = 0; i < parts.size(); i++) {
            String numbered = "[" + (i + 1) + "/" + parts.size() + "] " + parts.get(i);
            SendResult result = sendText(contact, numbered);
            if (!result.success()) {
                log.warn("[OutboundSender] 分片 {}/{} 发送失败，停止后续分片: {}",
                        i + 1, parts.size(), result.error());
                return result;
            }
        }
        // 全部分片发送成功：无单一 messageId，返回 success
        return SendResult.ok(null);
    }

    /**
     * 单条文本完整链路：客户级频率限制（令牌桶）→ 选通道发送 → 落库 OUT 消息 → 写审计。
     * 开启限流且超限时直接拒绝，不落库、不抛异常。
     */
    private SendResult sendText(Contact contact, String content) {
        String externalUserId = contact.getExternalUserId();
        if (appProperties.getWecom().isRateLimitEnabled()
                && !rateLimitService.tryAcquire(externalUserId)) {
            actionLogger.log(contact.getId(), "MESSAGE_SEND_RATE_LIMITED",
                    "externalUserId=" + externalUserId + " 发送频率超限，已拒绝");
            return SendResult.fail("发送频率超限，请稍后再试");
        }

        Channel channel = activeChannel();
        SendResult result = channel.send(new OutboundMessage(contact.getExternalUserId(), content));
        if (!result.success()) {
            actionLogger.log(contact.getId(), "MESSAGE_SEND_FAILED",
                    "channel=" + channel.channelType() + " error=" + result.error());
            return result;
        }

        // 落库外发消息（direction=OUT），msgId 用通道返回的 messageId
        MessageLog out = new MessageLog();
        out.setMsgId(result.messageId() == null ? "out-" + UUID.randomUUID() : result.messageId());
        out.setContactId(contact.getId());
        out.setDirection(Direction.OUT.name());
        out.setSenderType("AGENT");
        out.setContent(content);
        out.setMsgType("text");
        out.setChannelType(channel.channelType());
        out.setProcessed(Boolean.TRUE);
        out.touch();
        messageLogRepository.save(out);

        actionLogger.log(contact.getId(), "MESSAGE_SENT",
                "channel=" + channel.channelType() + " messageId=" + result.messageId() + " 内容=" + truncate(content));
        return result;
    }

    /**
     * 长文本分片（包可见静态，便于单测）：
     * 1) 长度 ≤ max 时不分片；
     * 2) 需截断时优先回退到最近换行符（\n 所在行尾，兼容 \n\n 段落），避免在句中硬切；
     * 3) 整段无换行边界时按字符硬切。
     */
    static List<String> splitContent(String content, int max) {
        List<String> parts = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return parts;
        }
        int effectiveMax = Math.max(max, 1);
        if (content.length() <= effectiveMax) {
            parts.add(content);
            return parts;
        }
        int start = 0;
        int length = content.length();
        while (start < length) {
            int end = Math.min(start + effectiveMax, length);
            if (end < length) {
                // 回退到 [start, end) 内最后一个换行符之后，把整段留给前片
                int newline = content.lastIndexOf('\n', end - 1);
                if (newline >= start) {
                    end = newline + 1;
                }
            }
            parts.add(content.substring(start, end));
            start = end;
        }
        return parts;
    }

    /** 当前激活的发送通道 */
    public Channel activeChannel() {
        String active = appProperties.getChannel().getActive();
        List<Channel> matched = channels.values().stream()
                .filter(c -> c.channelType().equalsIgnoreCase(active))
                .toList();
        if (matched.isEmpty()) {
            throw new IllegalStateException("未找到激活通道 app.channel.active=" + active
                    + "，可用通道: " + channels.values().stream().map(Channel::channelType).toList());
        }
        return matched.get(0);
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}

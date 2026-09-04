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

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 外发发送器：根据 app.channel.active 选择 mock / wecom 通道，
 * 统一负责：选通道 → 发送 → 落库 OUT 消息 → 写动作审计。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboundSender {

    private final Map<String, Channel> channels;
    private final ContactRepository contactRepository;
    private final MessageLogRepository messageLogRepository;
    private final ActionLogger actionLogger;
    private final AppProperties appProperties;

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

    private SendResult doSend(Contact contact, String content) {
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

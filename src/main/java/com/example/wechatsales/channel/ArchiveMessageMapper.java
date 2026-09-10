package com.example.wechatsales.channel;

import com.example.wechatsales.config.AppProperties;
import com.example.wechatsales.domain.Message;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 会话存档明文 JSON → 领域 {@link Message} 映射器。
 *
 * <p>明文结构（企微官方，见 README/DESIGN M1）：{@code msgid / action / from / tolist /
 * roomid / msgtime / msgtype / text.content / voice...}。</p>
 *
 * <p>方向映射：</p>
 * <ul>
 *   <li>单聊/群聊中 {@code from} 是外部联系人 → 客户来消息（IN），走完整编排链路；</li>
 *   <li>单聊中 {@code from} 是企业成员且 {@code tolist} 唯一外部客户 → 员工外发（OUT，
 *       仅补充上下文与审计，不触发回复；Agent 自己的外发因 msgId 幂等不会重复落库）；</li>
 *   <li>群聊中 {@code from} 是企业成员 → 经 {@link RoomMemberResolver} 归属群内唯一外部客户
 *       后同样映射为 OUT；无法唯一归属时跳过；</li>
 *   <li>图片/语音等非文本跳过（语音转文本 / 图片 OCR → 文本需接入外部语音识别/图片理解服务与企微媒体下载，属外部依赖项，非 M0/M1 纯代码实现范围）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArchiveMessageMapper {

    private final AppProperties appProperties;
    private final RoomMemberResolver roomMemberResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 单条存档明文 JSON → Message（无法定位客户 / 非文本 / 方向不符时 empty） */
    public Optional<Message> mapToMessage(String plainJson) {
        AppProperties.Wecom wecom = appProperties.getWecom();
        try {
            JsonNode root = objectMapper.readTree(plainJson);
            String msgId = text(root, "msgid");
            String msgtype = text(root, "msgtype");
            String roomid = text(root, "roomid");
            String from = text(root, "from");
            String content = text(root, msgtype.isEmpty() ? "text" : msgtype + ".content");

            if (msgId.isEmpty() || content.isEmpty()) {
                return Optional.empty();
            }
            // 仅映射文本消息；voice 转文本 / image OCR → 文本依赖外部语音识别/图片理解服务 + 企微媒体下载（外部依赖项）
            if (!"text".equals(msgtype)) {
                log.debug("[ArchiveMapper] 跳过非文本消息 msgId={} msgtype={}", msgId, msgtype);
                return Optional.empty();
            }

            boolean roomChat = roomid != null && !roomid.isBlank();
            if (!roomChat) {
                // 单聊
                if (wecom.looksExternal(from)) {
                    return Optional.of(Message.inbound(msgId, from, content, "wecom"));
                }
                // from 是企业成员 → 员工外发（OUT）：tolist 唯一外部客户时补充上下文；
                // Agent 自己外发的 msgId 已被 OutboundSender 落库，此处靠 msgId 幂等去重不重复处理
                Optional<String> customer = soleExternalInList(root.get("tolist"), wecom);
                if (customer.isPresent()) {
                    return Optional.of(Message.staffOutbound(msgId, customer.get(), content, "wecom"));
                }
                log.debug("[ArchiveMapper] 员工外发消息无法定位唯一客户，跳过 msgId={} from={}", msgId, from);
                return Optional.empty();
            }

            // 群聊：from 为外部联系人 → IN；from 为企业成员 → 经群成员映射归属唯一外部客户（OUT）
            if (wecom.looksExternal(from)) {
                return Optional.of(Message.inbound(msgId, from, content, "wecom"));
            }
            return roomMemberResolver.resolveExternalUserId(roomid)
                    .map(ext -> Message.staffOutbound(msgId, ext, content, "wecom"));
        } catch (Exception e) {
            log.warn("[ArchiveMapper] 存档明文解析失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** tolist 中的外部 id 过滤后恰为 1 个时返回该客户，否则 empty */
    private Optional<String> soleExternalInList(JsonNode tolist, AppProperties.Wecom wecom) {
        if (tolist == null || !tolist.isArray() || tolist.isEmpty()) {
            return Optional.empty();
        }
        String found = null;
        for (JsonNode n : tolist) {
            String id = n.asText("");
            if (id.isBlank() || !wecom.looksExternal(id)) {
                continue;
            }
            if (found != null) {
                return Optional.empty(); // 多个外部接收人，无法唯一归属
            }
            found = id;
        }
        return Optional.ofNullable(found);
    }

    private String text(JsonNode node, String dottedPath) {
        JsonNode cur = node;
        for (String part : dottedPath.split("\\.")) {
            if (cur == null || !cur.has(part)) {
                return "";
            }
            cur = cur.get(part);
        }
        return cur == null ? "" : cur.asText("");
    }
}

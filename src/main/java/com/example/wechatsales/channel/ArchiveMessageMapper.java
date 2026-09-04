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
 * roomid / msgtime / msgtype / text.content / voice...}。本类只把「可处理的文本类消息且
 * 能定位到外部客户」的消息映射为 IN 消息；员工外发、群聊无法定位说话人、图片/语音等
 * 消息跳过（语音转文本需接入语音识别服务，M1 留 TODO）。</p>
 *
 * <p>方向启发式：单聊中 {@code from} 是外部联系人 → 客户来消息（IN）；{@code from} 是
 * 企业成员 → 员工外发（OUT，跳过，Agent 自己外发的由 OutboundSender 落库审计）。
 * 群聊中 {@code from} 是外部联系人同样按 IN 处理；{@code from} 为企业成员时因缺少
 * 「群成员→客户」映射暂跳过。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArchiveMessageMapper {

    private final AppProperties appProperties;
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
            // M1：仅映射文本消息；voice 转文本、image OCR 等留 TODO（需语音识别/图片理解服务）
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
                // from 是企业成员 → 员工外发（OUT）。Agent 自己外发的 msgId 已被
                // OutboundSender 落库为 OUT；此处不投递，避免把员工消息当客户消息处理。
                log.debug("[ArchiveMapper] 员工外发消息，跳过 msgId={} from={}", msgId, from);
                return Optional.empty();
            }

            // 群聊：仅 from 为外部联系人时可定位客户（群成员完整映射 M1 后续 TODO）
            if (wecom.looksExternal(from)) {
                return Optional.of(Message.inbound(msgId, from, content, "wecom"));
            }
            log.debug("[ArchiveMapper] 群聊中企业成员发言，暂无法定位外部客户，跳过 msgId={} roomid={}", msgId, roomid);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("[ArchiveMapper] 存档明文解析失败: {}", e.getMessage());
            return Optional.empty();
        }
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

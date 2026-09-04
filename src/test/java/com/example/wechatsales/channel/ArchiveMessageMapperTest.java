package com.example.wechatsales.channel;

import com.example.wechatsales.config.AppProperties;
import com.example.wechatsales.domain.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话存档明文 JSON → 领域 Message 映射方向判断单测。
 * 覆盖：单聊客户来消息 / 员工外发跳过 / 群聊外部说话 / 群聊内部说话 / 非文本跳过。
 */
class ArchiveMessageMapperTest {

    private ArchiveMessageMapper mapper;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        // 默认 externalIdPrefixes 含 wm/wo/wxid_/wx_；internalUserIds 默认空
        props.setWecom(new AppProperties.Wecom());
        mapper = new ArchiveMessageMapper(props);
    }

    @Test
    void singleChatCustomerInbound() {
        String json = "{\"msgid\":\"CAO1\",\"action\":\"send\",\"from\":\"wmTESTCUSTOMER1\","
                + "\"tolist\":[\"zhangsan\"],\"msgtime\":1720000000,\"msgtype\":\"text\","
                + "\"text\":{\"content\":\"你好，想咨询报价\"}}";
        Optional<Message> mapped = mapper.mapToMessage(json);
        assertTrue(mapped.isPresent());
        assertEquals("CAO1", mapped.get().getMsgId());
        assertEquals("wmTESTCUSTOMER1", mapped.get().getContactExternalId());
        assertEquals("你好，想咨询报价", mapped.get().getContent());
        assertEquals("wecom", mapped.get().getChannelType());
    }

    @Test
    void employeeOutboundSkipped() {
        // from 是企业成员（无外部前缀、不在 internalUserIds 命中前缀规则）→ 员工外发，跳过
        String json = "{\"msgid\":\"CAO2\",\"action\":\"send\",\"from\":\"zhangsan\","
                + "\"tolist\":[\"wmTESTCUSTOMER1\"],\"msgtime\":1720000000,\"msgtype\":\"text\","
                + "\"text\":{\"content\":\"好的，方案稍后发您\"}}";
        Optional<Message> mapped = mapper.mapToMessage(json);
        assertTrue(mapped.isEmpty(), "员工外发消息不应作为 IN 投递");
    }

    @Test
    void roomChatExternalSpeakerInbound() {
        String json = "{\"msgid\":\"CAO3\",\"action\":\"send\",\"from\":\"wmTESTCUSTOMER2\","
                + "\"tolist\":[\"zhangsan\",\"lisi\"],\"roomid\":\"wrROOM123\","
                + "\"msgtime\":1720000000,\"msgtype\":\"text\","
                + "\"text\":{\"content\":\"群里讨论下方案\"}}";
        Optional<Message> mapped = mapper.mapToMessage(json);
        assertTrue(mapped.isPresent());
        assertEquals("wmTESTCUSTOMER2", mapped.get().getContactExternalId());
    }

    @Test
    void roomChatInternalSpeakerSkipped() {
        String json = "{\"msgid\":\"CAO4\",\"action\":\"send\",\"from\":\"zhangsan\","
                + "\"tolist\":[\"wmTESTCUSTOMER2\",\"lisi\"],\"roomid\":\"wrROOM123\","
                + "\"msgtime\":1720000000,\"msgtype\":\"text\","
                + "\"text\":{\"content\":\"我这边同步一下进展\"}}";
        Optional<Message> mapped = mapper.mapToMessage(json);
        assertTrue(mapped.isEmpty(), "群聊内部成员发言暂无法定位外部客户，应跳过");
    }

    @Test
    void nonTextSkipped() {
        String json = "{\"msgid\":\"CAO5\",\"action\":\"send\",\"from\":\"wmTESTCUSTOMER1\","
                + "\"tolist\":[\"zhangsan\"],\"msgtime\":1720000000,\"msgtype\":\"voice\","
                + "\"voice\":{\"voice_size\":1000,\"play_length\":5}}";
        Optional<Message> mapped = mapper.mapToMessage(json);
        assertTrue(mapped.isEmpty(), "语音等非文本 M1 暂不处理");
    }
}

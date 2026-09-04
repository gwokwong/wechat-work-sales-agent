package com.example.wechatsales.channel;

import com.example.wechatsales.domain.Message;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 企微回调两段 XML 解析单测：外层 Encrypt 提取 + 明文消息解析 / 事件忽略。
 */
class WeComCallbackXmlTest {

    private static final String CORP = "wxCORPID123";

    @Test
    void extractEncryptFromEnvelope() {
        String body = "<xml><ToUserName><![CDATA[" + CORP + "]]></ToUserName>"
                + "<Encrypt><![CDATA[ENCRYPTED_CONTENT_ABC123]]></Encrypt>"
                + "<AgentID><![CDATA[1000002]]></AgentID></xml>";
        assertEquals("ENCRYPTED_CONTENT_ABC123", WeComCallbackXml.extractEncrypt(body));
    }

    @Test
    void extractEncryptWithEmptyBodyMustFail() {
        assertThrows(IllegalArgumentException.class, () -> WeComCallbackXml.extractEncrypt("  "));
    }

    @Test
    void parseInboundTextMessage() {
        String xml = "<xml><ToUserName><![CDATA[" + CORP + "]]></ToUserName>"
                + "<FromUserName><![CDATA[wmEXTERNAL]]></FromUserName>"
                + "<CreateTime>1720000000</CreateTime>"
                + "<MsgType><![CDATA[text]]></MsgType>"
                + "<Content><![CDATA[你好，麻烦介绍下产品]]></Content>"
                + "<MsgId>5822502129954282712</MsgId>"
                + "<AgentID><![CDATA[1000002]]></AgentID></xml>";
        Optional<Message> message = WeComCallbackXml.parseInbound(xml);
        assertTrue(message.isPresent());
        assertEquals("5822502129954282712", message.get().getMsgId());
        assertEquals("wmEXTERNAL", message.get().getContactExternalId());
        assertEquals("你好，麻烦介绍下产品", message.get().getContent());
        assertEquals("wecom", message.get().getChannelType());
    }

    @Test
    void parseInboundEventIgnored() {
        String xml = "<xml><ToUserName><![CDATA[" + CORP + "]]></ToUserName>"
                + "<FromUserName><![CDATA[sys]]></FromUserName>"
                + "<CreateTime>1720000000</CreateTime>"
                + "<MsgType><![CDATA[event]]></MsgType>"
                + "<Event><![CDATA[change_external_contact]]></Event>"
                + "<ChangeType><![CDATA[add_external_contact]]></ChangeType></xml>";
        assertTrue(WeComCallbackXml.parseInbound(xml).isEmpty());
    }

    @Test
    void parseInboundImageIgnored() {
        String xml = "<xml><ToUserName><![CDATA[" + CORP + "]]></ToUserName>"
                + "<FromUserName><![CDATA[wmEXTERNAL]]></FromUserName>"
                + "<CreateTime>1720000000</CreateTime>"
                + "<MsgType><![CDATA[image]]></MsgType>"
                + "<PicUrl><![CDATA[http://example.com/a.jpg]]></PicUrl>"
                + "<MsgId>5822502129954282713</MsgId></xml>";
        assertTrue(WeComCallbackXml.parseInbound(xml).isEmpty());
    }
}

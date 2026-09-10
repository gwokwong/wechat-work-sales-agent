package com.example.wechatsales.channel;

import com.example.wechatsales.domain.Message;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 企微回调消息体解析（两段 XML）：
 * <ol>
 *   <li>外层安全模式请求体：{@code <xml><ToUserName>..</ToUserName><Encrypt>密文</Encrypt><AgentID>..</AgentID></xml>}
 *       → 提取 Encrypt 传给 WXBizMsgCrypt 解密；</li>
 *   <li>解密后明文 XML（微信客服/客户消息：MsgType=text、FromUserName=external_userid、
 *       MsgId、Content；事件：MsgType=event、Event=xxx 无 MsgId 不投递）。</li>
 * </ol>
 */
public final class WeComCallbackXml {

    private WeComCallbackXml() {
    }

    /** 从企微回调请求体中提取 Encrypt 密文 */
    public static String extractEncrypt(String postBody) {
        if (postBody == null || postBody.isBlank()) {
            throw new IllegalArgumentException("回调请求体为空");
        }
        Document doc = parseXml(postBody);
        Element root = doc.getDocumentElement();
        return textOf(root, "Encrypt");
    }

    /** 明文 XML → 领域 Message（仅文本客户消息；事件/媒体等返回 empty） */
    public static Optional<Message> parseInbound(String decryptedXml) {
        Document doc = parseXml(decryptedXml);
        Element root = doc.getDocumentElement();
        String msgType = textOf(root, "MsgType");
        String msgId = textOf(root, "MsgId");
        String from = textOf(root, "FromUserName");
        String content = textOf(root, "Content");

        if ("event".equalsIgnoreCase(msgType)) {
            return Optional.empty(); // 事件类（如 change_external_contact）不产生客户会话消息
        }
        if (!"text".equalsIgnoreCase(msgType) || msgId.isEmpty() || from.isEmpty() || content.isEmpty()) {
            return Optional.empty(); // 图片/语音/视频等非文本依赖素材下载 + 外部识别/理解服务转文本（外部依赖项）
        }
        return Optional.of(Message.inbound(msgId, from, content, "wecom"));
    }

    private static String textOf(Element root, String tag) {
        var node = root.getElementsByTagName(tag);
        return node.getLength() == 0 ? "" : node.item(0).getTextContent();
    }

    private static Document parseXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException("回调 XML 解析失败: " + e.getMessage(), e);
        }
    }
}

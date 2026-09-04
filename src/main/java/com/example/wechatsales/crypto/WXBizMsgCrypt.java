package com.example.wechatsales.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * 企业微信回调消息加解密工具，语义对齐官方 {@code WXBizMsgCrypt}（Java 版）。
 *
 * <p>官方加解密方案（{@code https://developer.work.weixin.qq.com/document/path/91144}）：</p>
 * <ul>
 *   <li>EncodingAESKey 固定 43 位（a-zA-Z0-9），Base64 解码（补 "="）得到 32 字节 AESKey；</li>
 *   <li>AES-256-CBC 加密，IV 取 AESKey 前 16 字节，PKCS7 填充；</li>
 *   <li>待加密原文 = 随机16字节 + 4字节网络序 msgLen + msg + receiveId(corpId)；</li>
 *   <li>签名 msg_signature = SHA1(字典序拼接 token、timestamp、nonce、msg_encrypt)，hex 小写。</li>
 * </ul>
 */
public class WXBizMsgCrypt {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String token;
    private final byte[] aesKey;
    private final String receiveId;

    /**
     * @param token          回调 Token（企微管理后台配置）
     * @param encodingAesKey 回调 EncodingAESKey（43 位）
     * @param receiveId      接收方标识：自建应用传 corpId
     */
    public WXBizMsgCrypt(String token, String encodingAesKey, String receiveId) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("callback token 不能为空");
        }
        if (encodingAesKey == null || encodingAesKey.length() != 43) {
            throw new IllegalArgumentException("EncodingAESKey 必须为 43 位，实际长度 "
                    + (encodingAesKey == null ? 0 : encodingAesKey.length()));
        }
        if (receiveId == null || receiveId.isBlank()) {
            throw new IllegalArgumentException("receiveId(corpId) 不能为空");
        }
        this.token = token;
        this.aesKey = Base64.getDecoder().decode(encodingAesKey + "=");
        if (this.aesKey.length != 32) {
            throw new IllegalArgumentException("EncodingAESKey 解码后必须为 32 字节，实际 "
                    + this.aesKey.length);
        }
        this.receiveId = receiveId;
    }

    /** 校验 URL（GET）：验签通过后返回解密出的 echostr 明文（企微 URL 验证用） */
    public String verifyUrl(String msgSignature, String timestamp, String nonce, String echoStr) {
        checkSignature(msgSignature, timestamp, nonce, echoStr);
        byte[] plain = AesCbc.decrypt(Base64.getDecoder().decode(echoStr), aesKey);
        return extractMsg(plain);
    }

    /** 解密 POST 回调数据包：先验签，再解密得到明文 XML（内部消息体） */
    public String decryptMsg(String msgSignature, String timestamp, String nonce, String encryptMsg) {
        checkSignature(msgSignature, timestamp, nonce, encryptMsg);
        byte[] plain = AesCbc.decrypt(Base64.getDecoder().decode(encryptMsg), aesKey);
        return extractMsg(plain);
    }

    /** 加密被动回复并生成签名，返回 {@code <xml><Encrypt>...</Encrypt><MsgSignature>...</MsgSignature>...}</xml> */
    public String encryptMsg(String replyMsg, String timestamp, String nonce) {
        byte[] random16 = new byte[16];
        RANDOM.nextBytes(random16);
        byte[] msgBytes = replyMsg.getBytes(StandardCharsets.UTF_8);
        byte[] full = new byte[16 + 4 + msgBytes.length + receiveId.getBytes(StandardCharsets.UTF_8).length];
        System.arraycopy(random16, 0, full, 0, 16);
        full[16] = (byte) ((msgBytes.length >> 24) & 0xFF);
        full[17] = (byte) ((msgBytes.length >> 16) & 0xFF);
        full[18] = (byte) ((msgBytes.length >> 8) & 0xFF);
        full[19] = (byte) (msgBytes.length & 0xFF);
        System.arraycopy(msgBytes, 0, full, 20, msgBytes.length);
        System.arraycopy(receiveId.getBytes(StandardCharsets.UTF_8), 0, full, 20 + msgBytes.length,
                receiveId.getBytes(StandardCharsets.UTF_8).length);

        byte[] encrypt = AesCbc.encrypt(full, aesKey);
        String encryptBase64 = Base64.getEncoder().encodeToString(encrypt);
        String signature = genSignature(timestamp, nonce, encryptBase64);
        return "<xml>"
                + "<Encrypt><![CDATA[" + encryptBase64 + "]]></Encrypt>"
                + "<MsgSignature><![CDATA[" + signature + "]]></MsgSignature>"
                + "<TimeStamp>" + timestamp + "</TimeStamp>"
                + "<Nonce><![CDATA[" + nonce + "]]></Nonce>"
                + "</xml>";
    }

    /** 生成签名：sha1(字典序拼接 token, timestamp, nonce, encrypt) */
    public String genSignature(String timestamp, String nonce, String encrypt) {
        List<String> list = new ArrayList<>(Arrays.asList(token, timestamp, nonce, encrypt));
        Collections.sort(list);
        String joined = String.join("", list);
        return sha1Hex(joined);
    }

    /** 验签：计算值与企微传入 msg_signature 必须一致 */
    public void checkSignature(String msgSignature, String timestamp, String nonce, String encrypt) {
        String expected = genSignature(timestamp, nonce, encrypt);
        if (expected.equalsIgnoreCase(msgSignature)) {
            return;
        }
        throw new SecurityException("回调签名校验失败 expected=" + expected + " actual=" + msgSignature);
    }

    /**
     * 从解密原文中截取消息体：前 16 字节随机串 + 4 字节网络序长度 + msg；
     * 同时校验尾部 receiveId 与配置一致。
     */
    private String extractMsg(byte[] plain) {
        if (plain.length < 20) {
            throw new SecurityException("解密原文长度异常: " + plain.length);
        }
        int msgLen = ((plain[16] & 0xFF) << 24)
                | ((plain[17] & 0xFF) << 16)
                | ((plain[18] & 0xFF) << 8)
                | (plain[19] & 0xFF);
        if (msgLen <= 0 || plain.length < 20 + msgLen) {
            throw new SecurityException("解密原文长度与 msgLen 不匹配: msgLen=" + msgLen
                    + " total=" + plain.length);
        }
        String msg = new String(plain, 20, msgLen, StandardCharsets.UTF_8);
        String actualReceiveId = new String(plain, 20 + msgLen, plain.length - 20 - msgLen,
                StandardCharsets.UTF_8);
        if (!receiveId.equals(actualReceiveId)) {
            throw new SecurityException("receiveId 不匹配，期望=" + receiveId + " 实际=" + actualReceiveId);
        }
        return msg;
    }

    private static String sha1Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-1 计算失败", e);
        }
    }
}

package com.example.wechatsales.crypto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 企微回调加解密往返自测（WXBizMsgCrypt 语义）。
 *
 * <p>采用「自造样例往返」验证：encryptMsg → 提取 Encrypt → decryptMsg 还原原文；
 * verifyUrl 对 echostr 同构往返；篡改签名必须抛 SecurityException。
 * EncodingAESKey 使用官方文档示例格式 43 位串，但用例为本地算法验证，
 * 不代表已与真实企微联调。</p>
 */
class WXBizMsgCryptTest {

    private static final String TOKEN = "QDG6eK";
    private static final String CORP_ID = "wx5823bf96d3bd56c7";
    private static final String AES_KEY_43 = "jWmYm7qr5nMoAUwZRjGtBxmz3KA1tkAj3ykkR6q2B2C";
    private static final String TIMESTAMP = "1409659589";
    private static final String NONCE = "263014780";

    private WXBizMsgCrypt newCrypt() {
        return new WXBizMsgCrypt(TOKEN, AES_KEY_43, CORP_ID);
    }

    @Test
    void encryptDecryptRoundTrip() {
        String msg = "<xml><ToUserName><![CDATA[" + CORP_ID + "]]></ToUserName>"
                + "<FromUserName><![CDATA[wmTEST]]></FromUserName>"
                + "<CreateTime>1720000000</CreateTime>"
                + "<MsgType><![CDATA[text]]></MsgType>"
                + "<Content><![CDATA[hello 企业微信 销售]]></Content>"
                + "<MsgId>5822502129954282712</MsgId></xml>";
        WXBizMsgCrypt crypt = newCrypt();
        String envelope = crypt.encryptMsg(msg, TIMESTAMP, NONCE);
        String encrypt = com.example.wechatsales.channel.WeComCallbackXml.extractEncrypt(envelope);
        assertTrue(encrypt.length() > 24, "密文不应为空");

        // 用企微传入路径同款方式解密（自带验签）
        String decrypted = crypt.decryptMsg(crypt.genSignature(TIMESTAMP, NONCE, encrypt),
                TIMESTAMP, NONCE, encrypt);
        assertEquals(msg, decrypted);
    }

    @Test
    void verifyUrlRoundTrip() {
        WXBizMsgCrypt crypt = newCrypt();
        String echoPlain = "random_echostr_123456";
        String envelope = crypt.encryptMsg(echoPlain, TIMESTAMP, NONCE);
        String encrypt = com.example.wechatsales.channel.WeComCallbackXml.extractEncrypt(envelope);
        String decrypted = crypt.verifyUrl(crypt.genSignature(TIMESTAMP, NONCE, encrypt),
                TIMESTAMP, NONCE, encrypt);
        assertEquals(echoPlain, decrypted);
    }

    @Test
    void badSignatureMustFail() {
        WXBizMsgCrypt crypt = newCrypt();
        String envelope = crypt.encryptMsg("hello", TIMESTAMP, NONCE);
        String encrypt = com.example.wechatsales.channel.WeComCallbackXml.extractEncrypt(envelope);
        SecurityException ex = assertThrows(SecurityException.class,
                () -> crypt.decryptMsg("deadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
                        TIMESTAMP, NONCE, encrypt));
        assertTrue(ex.getMessage().contains("签名"));
    }

    @Test
    void invalidKeyLengthMustFail() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new WXBizMsgCrypt(TOKEN, "too-short", CORP_ID));
        assertTrue(ex.getMessage().contains("43"));
    }

    @Test
    void tamperedCipherMustFail() {
        WXBizMsgCrypt crypt = newCrypt();
        String envelope = crypt.encryptMsg("hello", TIMESTAMP, NONCE);
        String encrypt = com.example.wechatsales.channel.WeComCallbackXml.extractEncrypt(envelope);
        // 篡改密文最后一位，保留合法签名 → 解密会失败或 receiveId 校验失败
        String tampered = encrypt.substring(0, encrypt.length() - 2) + "AA";
        assertThrows(RuntimeException.class,
                () -> crypt.decryptMsg(crypt.genSignature(TIMESTAMP, NONCE, tampered),
                        TIMESTAMP, NONCE, tampered));
    }
}

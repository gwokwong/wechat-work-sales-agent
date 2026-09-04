package com.example.wechatsales.crypto;

import javax.crypto.Cipher;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 企微会话存档消息解密工具（对齐官方 {@code DecryptData} 语义）。
 *
 * <p>官方解密方案（{@code https://developer.work.weixin.qq.com/document/17312}）：</p>
 * <ol>
 *   <li>Base64 解码 {@code encrypt_random_key}，用企微后台配置的 RSA 私钥
 *       （PKCS#1 v1.5 填充，2048bit）解密得到 32 字节会话密钥 randomKey；</li>
 *   <li>以 randomKey 为密钥、randomKey 前 16 字节为 IV，
 *       对 {@code encrypt_chat_msg}（Base64）做 AES-256-CBC + PKCS7 解密，
 *       得到消息明文 JSON。</li>
 * </ol>
 *
 * <p>私钥支持两种 PEM 头：{@code BEGIN PRIVATE KEY}（PKCS8）与
 * {@code BEGIN RSA PRIVATE KEY}（PKCS1，官方默认格式）。PKCS1 在加载时手工包装为
 * PKCS8（RSA OID + NULL + OCTET STRING），避免引入第三方依赖。</p>
 */
public final class ArchiveDecryptor {

    private static final Pattern PEM_BLOCK = Pattern.compile(
            "-----BEGIN ([A-Z ]+)-----(.*?)-----END \\1-----", Pattern.DOTALL);

    private ArchiveDecryptor() {
    }

    /**
     * 解密单条会话存档消息。
     *
     * @param encryptRandomKey getchatdata 返回的 encrypt_random_key（Base64）
     * @param encryptChatMsg   getchatdata 返回的 encrypt_chat_msg（Base64）
     * @param privateKeyPem    企微后台配置的 RSA 私钥（PKCS1 或 PKCS8 PEM 文本）
     * @return 消息明文 JSON
     */
    public static String decryptChatMsg(String encryptRandomKey, String encryptChatMsg,
                                        String privateKeyPem) {
        byte[] randomKey = decryptRandomKey(encryptRandomKey, loadPrivateKey(privateKeyPem));
        if (randomKey.length != 32) {
            throw new IllegalStateException("RSA 解密得到的会话密钥长度异常，期望 32 字节，实际 "
                    + randomKey.length);
        }
        return AesCbc.decryptToString(encryptChatMsg, randomKey);
    }

    /** RSA/ECB/PKCS1Padding 解密会话密钥（encrypt_random_key 为 Base64） */
    public static byte[] decryptRandomKey(String encryptRandomKey, PrivateKey privateKey) {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, privateKey);
            return cipher.doFinal(Base64.getDecoder().decode(encryptRandomKey));
        } catch (Exception e) {
            throw new IllegalStateException("RSA 解密会话密钥失败: " + e.getMessage(), e);
        }
    }

    /** 加载 RSA 私钥：自动识别 PKCS1 / PKCS8 PEM */
    public static PrivateKey loadPrivateKey(String pem) {
        try {
            Matcher m = PEM_BLOCK.matcher(pem);
            if (!m.find()) {
                throw new IllegalArgumentException("无法识别的 PEM 私钥格式（缺少 BEGIN/END 行）");
            }
            String type = m.group(1).trim();
            byte[] der = Base64.getDecoder().decode(m.group(2).replaceAll("\\s", ""));
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            if ("RSA PRIVATE KEY".equals(type)) {
                der = wrapPkcs1ToPkcs8(der);
            } else if (!"PRIVATE KEY".equals(type)) {
                throw new IllegalArgumentException("不支持的私钥 PEM 类型: " + type);
            }
            return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("RSA 私钥加载失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将 PKCS1 RSAPrivateKey DER 包装为 PKCS8 PrivateKeyInfo DER：
     * SEQUENCE { INTEGER 0, SEQUENCE { OID rsaEncryption, NULL }, OCTET STRING pkcs1Der }。
     */
    private static byte[] wrapPkcs1ToPkcs8(byte[] pkcs1Der) throws Exception {
        // 1.2.840.113549.1.1.1 = rsaEncryption
        byte[] oid = {0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7,
                0x0D, 0x01, 0x01, 0x01};
        byte[] nullTag = {0x05, 0x00};
        ByteArrayOutputStream alg = new ByteArrayOutputStream();
        alg.writeBytes(oid);
        alg.writeBytes(nullTag);
        byte[] algSeq = derSequence(alg.toByteArray());

        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        inner.write(new byte[]{0x02, 0x01, 0x00}); // INTEGER 0
        inner.writeBytes(algSeq);
        inner.writeBytes(derOctetString(pkcs1Der));

        return derSequence(inner.toByteArray());
    }

    private static byte[] derSequence(byte[] content) {
        return derWrap(0x30, content);
    }

    private static byte[] derOctetString(byte[] content) {
        return derWrap(0x04, content);
    }

    private static byte[] derWrap(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        writeDerLength(out, content.length);
        out.writeBytes(content);
        return out.toByteArray();
    }

    private static void writeDerLength(ByteArrayOutputStream out, int length) {
        if (length < 0x80) {
            out.write(length);
        } else if (length <= 0xFF) {
            out.write(0x81);
            out.write(length);
        } else if (length <= 0xFFFF) {
            out.write(0x82);
            out.write((length >> 8) & 0xFF);
            out.write(length & 0xFF);
        } else {
            out.write(0x83);
            out.write((length >> 16) & 0xFF);
            out.write((length >> 8) & 0xFF);
            out.write(length & 0xFF);
        }
    }

    /** 便于校验密钥加载是否成功 */
    public static boolean isPemUsable(String pem) {
        try {
            return loadPrivateKey(pem) != null;
        } catch (Exception e) {
            return false;
        }
    }
}

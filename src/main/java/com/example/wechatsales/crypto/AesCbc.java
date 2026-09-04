package com.example.wechatsales.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 企微统一对称加密工具：AES-256-CBC + PKCS#7 去填充。
 *
 * <p>企业微信两套加解密（会话存档、回调消息）的对称层参数完全一致：</p>
 * <ul>
 *   <li>算法：AES-256-CBC（JDK 的 PKCS5Padding 与 PKCS7 在 16 字节块下等价）；</li>
 *   <li>密钥：32 字节；IV：取密钥前 16 字节；</li>
 *   <li>密文：Base64 编码。</li>
 * </ul>
 */
public final class AesCbc {

    private AesCbc() {
    }

    /** AES-256-CBC 解密，IV 自动取 key 前 16 字节，返回 PKCS7 去填充后的明文 */
    public static byte[] decrypt(byte[] encrypted, byte[] key) {
        try {
            if (key == null || key.length != 32) {
                throw new IllegalArgumentException("AES key 必须为 32 字节，实际 " + (key == null ? 0 : key.length));
            }
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(key, 0, 16));
            return cipher.doFinal(encrypted);
        } catch (Exception e) {
            throw new IllegalStateException("AES-256-CBC 解密失败: " + e.getMessage(), e);
        }
    }

    /** AES-256-CBC 加密，IV 自动取 key 前 16 字节，输出 PKCS7 填充后密文（原始字节，由调用方决定是否 Base64） */
    public static byte[] encrypt(byte[] plain, byte[] key) {
        try {
            if (key == null || key.length != 32) {
                throw new IllegalArgumentException("AES key 必须为 32 字节，实际 " + (key == null ? 0 : key.length));
            }
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(key, 0, 16));
            return cipher.doFinal(plain);
        } catch (Exception e) {
            throw new IllegalStateException("AES-256-CBC 加密失败: " + e.getMessage(), e);
        }
    }

    /** 便捷方法：解密后按 UTF-8 转字符串（Base64 密文入参） */
    public static String decryptToString(String base64Encrypted, byte[] key) {
        byte[] plain = decrypt(Base64.getDecoder().decode(base64Encrypted), key);
        return new String(plain, StandardCharsets.UTF_8);
    }

    /** 便捷方法：明文 UTF-8 转 Base64 密文 */
    public static String encryptToString(String plain, byte[] key) {
        byte[] cipher = encrypt(plain.getBytes(StandardCharsets.UTF_8), key);
        return Base64.getEncoder().encodeToString(cipher);
    }
}

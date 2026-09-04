package com.example.wechatsales.crypto;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话存档解密自测（ArchiveDecryptor 语义）。
 *
 * <p>本地构造与企微一致的加密链：RSA(2048 PKCS1 公钥) 加密 32 字节会话密钥 →
 * AES-256-CBC(PKCS7, IV=key 前 16 字节) 加密消息明文 → decryptChatMsg 全链路还原。
 * 私钥覆盖 PKCS1（官方默认格式，测试资源自造样例）与 PKCS8 两种 PEM。</p>
 */
class ArchiveDecryptorTest {

    private static final String JSON_PLAIN =
            "{\"msgid\":\"CAO1234567890\",\"action\":\"send\",\"from\":\"zhangsan\","
                    + "\"tolist\":[\"wmTESTCUSTOMER\"],\"msgtime\":1720000000,"
                    + "\"msgtype\":\"text\",\"text\":{\"content\":\"您好，这是存档文本\"}}";

    @Test
    void decryptWithPkcs1PemRoundTrip() throws Exception {
        String pkcs1Pem = loadResource("pkcs1-test-key.pem");
        PrivateKey privateKey = ArchiveDecryptor.loadPrivateKey(pkcs1Pem);
        assertNotNull(privateKey);
        assertTrue(privateKey instanceof RSAPrivateCrtKey, "PKCS1 应还原出 CRT 私钥");

        String[] packed = packAsWeCom(privateKey);
        String plain = ArchiveDecryptor.decryptChatMsg(packed[0], packed[1], pkcs1Pem);
        assertEquals(JSON_PLAIN, plain);
    }

    @Test
    void decryptWithPkcs8PemRoundTrip() throws Exception {
        String pkcs1Pem = loadResource("pkcs1-test-key.pem");
        PrivateKey pkcs1Key = ArchiveDecryptor.loadPrivateKey(pkcs1Pem);

        // 用私钥的 PKCS8 编码构造 PKCS8 PEM
        String pkcs8Pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(pkcs1Key.getEncoded())
                + "\n-----END PRIVATE KEY-----";
        assertTrue(ArchiveDecryptor.isPemUsable(pkcs8Pem));
        assertEquals(pkcs1Key.getEncoded().length,
                ArchiveDecryptor.loadPrivateKey(pkcs8Pem).getEncoded().length);

        String[] packed = packAsWeCom(pkcs1Key);
        String plain = ArchiveDecryptor.decryptChatMsg(packed[0], packed[1], pkcs8Pem);
        assertEquals(JSON_PLAIN, plain);
    }

    /** 按企微方式打包：RSA PKCS1 加密 randomKey → encrypt_random_key；AES-CBC 加密 msg → encrypt_chat_msg */
    private String[] packAsWeCom(PrivateKey privateKey) throws Exception {
        RSAPrivateCrtKey crt = (RSAPrivateCrtKey) privateKey;
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PublicKey publicKey = keyFactory.generatePublic(
                new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));

        // 会话密钥 32 字节（企微实际为随机生成，此处固定值便于断言）
        byte[] randomKey = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        assertEquals(32, randomKey.length);

        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.ENCRYPT_MODE, publicKey);
        String encryptRandomKey = Base64.getEncoder().encodeToString(rsa.doFinal(randomKey));

        String encryptChatMsg = AesCbc.encryptToString(JSON_PLAIN, randomKey);
        return new String[]{encryptRandomKey, encryptChatMsg};
    }

    private static String loadResource(String name) throws IOException {
        try (InputStream in = ArchiveDecryptorTest.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("测试资源不存在: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void pkcs8EncodedKeyParsable() throws Exception {
        // 验证 PKCS8 解码路径可直接被 KeyFactory 读取（Java 默认编码格式）
        Path pem = Path.of("src/test/resources/pkcs1-test-key.pem");
        String pkcs1Pem = Files.readString(pem);
        PrivateKey key = ArchiveDecryptor.loadPrivateKey(pkcs1Pem);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        assertNotNull(kf.generatePrivate(new PKCS8EncodedKeySpec(key.getEncoded())));
    }
}

package com.example.wechatsales.web;

import com.example.wechatsales.channel.WeComChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * 企微回调 Controller 单测：URL 验证回显、验签失败 401、回调立即 ack。
 * 加解密细节由 crypto 包既有测试覆盖，此处 mock 通道层。
 */
@ExtendWith(MockitoExtension.class)
class WeComCallbackControllerTest {

    @Mock
    private WeComChannel weComChannel;

    private WeComCallbackController controller;

    @BeforeEach
    void setUp() {
        controller = new WeComCallbackController(weComChannel);
    }

    @Test
    void verifyUrlEchoesPlainAndRejectsBadSignature() {
        when(weComChannel.verifyUrl("sig-1", "ts", "nonce", "echostr-enc"))
                .thenReturn("echostr-plain")
                .thenThrow(new IllegalStateException("解密失败"));

        ResponseEntity<String> ok = controller.verifyUrl("sig-1", "ts", "nonce", "echostr-enc");
        assertEquals(HttpStatus.OK, ok.getStatusCode());
        assertEquals("echostr-plain", ok.getBody());

        // 第二次同参数调用 → 通道验签失败 → 401
        ResponseEntity<String> bad = controller.verifyUrl("sig-1", "ts", "nonce", "echostr-enc");
        assertEquals(HttpStatus.UNAUTHORIZED, bad.getStatusCode());
    }

    @Test
    void receiveAcksSuccessAndRejectsSecurityFailure() {
        String body = "<xml><ToUserName>corpId</ToUserName><Encrypt>enc-abc</Encrypt><AgentID>1</AgentID></xml>";
        when(weComChannel.receiveCallback("sig-2", "ts2", "nonce2", "enc-abc")).thenReturn("success");
        ResponseEntity<String> ack = controller.receive("sig-2", "ts2", "nonce2", body);
        assertEquals(HttpStatus.OK, ack.getStatusCode());
        assertEquals("success", ack.getBody());

        String evilBody = "<xml><ToUserName>corpId</ToUserName><Encrypt>evil</Encrypt><AgentID>1</AgentID></xml>";
        when(weComChannel.receiveCallback("sig-3", "ts3", "nonce3", "evil"))
                .thenThrow(new SecurityException("msg signature verification failed"));
        ResponseEntity<String> rejected = controller.receive("sig-3", "ts3", "nonce3", evilBody);
        assertEquals(HttpStatus.UNAUTHORIZED, rejected.getStatusCode());
    }
}

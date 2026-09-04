package com.example.wechatsales.web;

import com.example.wechatsales.channel.WeComChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 企业微信回调入口（URL 验证 + 消息/事件回调）。
 *
 * <ul>
 *   <li>GET /wecom/callback?msg_signature=&amp;timestamp=&amp;nonce=&amp;echostr=
 *       → 验签解密回显 echostr 明文（企微后台 URL 验证）；</li>
 *   <li>POST /wecom/callback → 验签解密，异步投 MessageBus，立即返回 "success"
 *       （企微 5 秒 ack 硬约束）；验签失败返回 401。</li>
 * </ul>
 *
 * <p>仅在 {@code app.wecom.enabled=true} 时暴露路由；未启用时本地址 404。</p>
 */
@Slf4j
@RestController
@RequestMapping("/wecom/callback")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.wecom", name = "enabled", havingValue = "true")
public class WeComCallbackController {

    private final WeComChannel weComChannel;

    /** URL 验证：解密 echostr 并回显明文 */
    @GetMapping
    public ResponseEntity<String> verifyUrl(@RequestParam("msg_signature") String msgSignature,
                                            @RequestParam("timestamp") String timestamp,
                                            @RequestParam("nonce") String nonce,
                                            @RequestParam("echostr") String echoStr) {
        try {
            String plain = weComChannel.verifyUrl(msgSignature, timestamp, nonce, echoStr);
            log.info("[WeComCallback] URL 验证通过，echostr 解密成功");
            return ResponseEntity.ok(plain);
        } catch (Exception e) {
            log.warn("[WeComCallback] URL 验证失败: {}", e.getMessage());
            return ResponseEntity.status(401).body("invalid signature");
        }
    }

    /** 消息/事件回调：立即 ack "success"，解密内容异步投递 */
    @PostMapping(produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> receive(@RequestParam("msg_signature") String msgSignature,
                                          @RequestParam("timestamp") String timestamp,
                                          @RequestParam("nonce") String nonce,
                                          @RequestBody String body) {
        try {
            String encrypt = com.example.wechatsales.channel.WeComCallbackXml.extractEncrypt(body);
            String ack = weComChannel.receiveCallback(msgSignature, timestamp, nonce, encrypt);
            return ResponseEntity.ok(ack == null ? "success" : ack);
        } catch (SecurityException e) {
            log.warn("[WeComCallback] 回调验签失败: {}", e.getMessage());
            return ResponseEntity.status(401).body("invalid signature");
        } catch (Exception e) {
            log.warn("[WeComCallback] 回调处理异常: {}", e.getMessage());
            // 无法解密的请求视为非法请求，拒绝并记录；合法但内部处理失败的由异步兜底
            return ResponseEntity.status(401).body("invalid request");
        }
    }
}

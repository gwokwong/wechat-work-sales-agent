package com.example.wechatsales.rest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 管理端演示认证接口：登录换取 token 与当前用户信息。
 * 演示账号 admin / admin123；token 仅为演示串，不持久化、不强制鉴权。
 */
@RestController
@RequestMapping("/api")
public class AuthController {

    private static final String DEMO_USERNAME = "admin";
    private static final String DEMO_PASSWORD = "admin123";

    /** 登录：演示账号校验通过返回 token / refreshToken；失败统一返回 400 */
    @PostMapping("/auth/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest req) {
        if (!DEMO_USERNAME.equals(req.userName()) || !DEMO_PASSWORD.equals(req.password())) {
            return ApiResponse.fail(400, "用户名或密码错误");
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("refreshToken", "refresh-" + token);
        return ApiResponse.ok(data);
    }

    /** 当前用户信息（sale-ui 直连演示用，不强制校验 Authorization） */
    @GetMapping("/user/info")
    public ApiResponse<Map<String, Object>> userInfo() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("buttons", List.of());
        data.put("roles", List.of("R_SUPER", "R_ADMIN"));
        data.put("userId", 1L);
        data.put("userName", DEMO_USERNAME);
        data.put("email", "admin@demo.com");
        data.put("avatar", "");
        return ApiResponse.ok(data);
    }

    public record LoginRequest(@NotBlank String userName, @NotBlank String password) {
    }
}

package com.example.wechatsales.rest;

import com.example.wechatsales.service.SystemAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
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
 * 管理端认证接口：登录换取 token 与当前用户信息、注册新用户。
 * 登录校验用户表（密码 SHA-256）；用户表为空时兼容演示账号 admin / admin123。
 * token 仅为演示串，不持久化、不强制鉴权。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AuthController {

    private final SystemAdminService systemAdminService;

    /** 登录：校验用户表，通过后返回 token / refreshToken；失败统一返回 400 */
    @PostMapping("/auth/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest req) {
        if (!systemAdminService.authenticate(req.userName(), req.password())) {
            return ApiResponse.fail(400, "用户名或密码错误");
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("refreshToken", "refresh-" + token);
        return ApiResponse.ok(data);
    }

    /** 注册：创建系统用户，重复用户名返回 400 */
    @PostMapping("/auth/register")
    public ApiResponse<Map<String, Object>> register(@Valid @RequestBody LoginRequest req) {
        return ApiResponse.ok(systemAdminService.register(req.userName(), req.password()));
    }

    /** 当前用户信息（sale-ui 直连演示用，不强制校验 Authorization） */
    @GetMapping("/user/info")
    public ApiResponse<Map<String, Object>> userInfo() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("buttons", List.of());
        data.put("roles", List.of("R_SUPER", "R_ADMIN"));
        data.put("userId", 1L);
        data.put("userName", "admin");
        data.put("email", "admin@demo.com");
        data.put("avatar", "");
        return ApiResponse.ok(data);
    }

    public record LoginRequest(
            @NotBlank String userName,
            @NotBlank @Size(min = 4, max = 64) String password) {
    }
}

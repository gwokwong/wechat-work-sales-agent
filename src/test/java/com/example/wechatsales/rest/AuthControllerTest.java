package com.example.wechatsales.rest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理端认证接口单测：正确账号登录、错误密码 400、用户信息含 R_SUPER。
 */
class AuthControllerTest {

    private final AuthController controller = new AuthController();

    @Test
    void loginWithCorrectAccountReturns200AndToken() {
        ApiResponse<Map<String, Object>> resp =
                controller.login(new AuthController.LoginRequest("admin", "admin123"));
        assertEquals(200, resp.code());
        assertNotNull(resp.data());
        Object token = resp.data().get("token");
        assertNotNull(token);
        assertFalse(token.toString().isBlank());
        assertNotNull(resp.data().get("refreshToken"));
    }

    @Test
    void loginWithWrongPasswordReturns400() {
        ApiResponse<Map<String, Object>> wrongPass =
                controller.login(new AuthController.LoginRequest("admin", "wrong"));
        assertEquals(400, wrongPass.code());
        assertEquals("用户名或密码错误", wrongPass.message());
        assertNull(wrongPass.data());

        ApiResponse<Map<String, Object>> wrongUser =
                controller.login(new AuthController.LoginRequest("nobody", "admin123"));
        assertEquals(400, wrongUser.code());
        assertEquals("用户名或密码错误", wrongUser.message());
        assertNull(wrongUser.data());
    }

    @Test
    void userInfoReturnsSuperRole() {
        ApiResponse<Map<String, Object>> resp = controller.userInfo();
        assertEquals(200, resp.code());
        assertNotNull(resp.data());
        assertTrue(resp.data().get("roles") instanceof List);
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) resp.data().get("roles");
        assertTrue(roles.contains("R_SUPER"));
        assertTrue(roles.contains("R_ADMIN"));
        assertEquals("admin", resp.data().get("userName"));
        assertEquals(1L, resp.data().get("userId"));
    }
}

package com.example.wechatsales.rest;

import com.example.wechatsales.domain.SysRole;
import com.example.wechatsales.service.SystemAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统管理域接口：角色增删改查、角色权限加载/保存、菜单增删改查、用户列表。
 * 统一响应 {@code {code, message, data}}，分页返回 {@code {records, current, size, total}}。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SystemAdminController {

    private final SystemAdminService systemAdminService;

    // ---------- 角色 ----------

    @GetMapping("/role/list")
    public ApiResponse<Map<String, Object>> roleList(
            @RequestParam(required = false) Integer current,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String roleName,
            @RequestParam(required = false) String roleCode,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) String enabled) {
        Map<String, Object> params = new HashMap<>();
        params.put("current", current);
        params.put("size", size);
        params.put("roleName", roleName);
        params.put("roleCode", roleCode);
        params.put("description", description);
        params.put("enabled", enabled);
        return ApiResponse.ok(systemAdminService.roleList(params));
    }

    @PostMapping("/role")
    public ApiResponse<SysRole> createRole(@RequestBody RoleBody body) {
        return ApiResponse.ok(systemAdminService.createRole(
                body.getRoleName(), body.getRoleCode(), body.getDescription(), body.getEnabled()));
    }

    @PutMapping("/role/{id}")
    public ApiResponse<SysRole> updateRole(@PathVariable Long id, @RequestBody RoleBody body) {
        return ApiResponse.ok(systemAdminService.updateRole(
                id, body.getRoleName(), body.getRoleCode(), body.getDescription(), body.getEnabled()));
    }

    @DeleteMapping("/role/{id}")
    public ApiResponse<Void> deleteRole(@PathVariable Long id) {
        systemAdminService.deleteRole(id);
        return ApiResponse.ok(null);
    }

    // ---------- 角色权限 ----------

    @GetMapping("/role/{id}/permissions")
    public ApiResponse<List<String>> rolePermissions(@PathVariable Long id) {
        return ApiResponse.ok(systemAdminService.rolePermissions(id));
    }

    @PutMapping("/role/{id}/permissions")
    public ApiResponse<Void> saveRolePermissions(@PathVariable Long id, @RequestBody PermissionBody body) {
        systemAdminService.saveRolePermissions(id, body.getPermissions());
        return ApiResponse.ok(null);
    }

    // ---------- 菜单 ----------

    @GetMapping("/v3/system/menus/simple")
    public ApiResponse<List<Map<String, Object>>> menuTree() {
        return ApiResponse.ok(systemAdminService.menuTree());
    }

    @PostMapping("/menu")
    public ApiResponse<Map<String, Object>> createMenu(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(Map.of("id", systemAdminService.createMenu(body).getId()));
    }

    @PutMapping("/menu/{id}")
    public ApiResponse<Map<String, Object>> updateMenu(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(Map.of("id", systemAdminService.updateMenu(id, body).getId()));
    }

    @DeleteMapping("/menu/{id}")
    public ApiResponse<Void> deleteMenu(@PathVariable Long id) {
        systemAdminService.deleteMenu(id);
        return ApiResponse.ok(null);
    }

    // ---------- 用户列表 ----------

    @GetMapping("/user/list")
    public ApiResponse<Map<String, Object>> userList(
            @RequestParam(required = false) Integer current,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String userName,
            @RequestParam(required = false) String userGender,
            @RequestParam(required = false) String userPhone,
            @RequestParam(required = false) String userEmail,
            @RequestParam(required = false) String status) {
        Map<String, Object> params = new HashMap<>();
        params.put("current", current);
        params.put("size", size);
        params.put("userName", userName);
        params.put("userGender", userGender);
        params.put("userPhone", userPhone);
        params.put("userEmail", userEmail);
        params.put("status", status);
        return ApiResponse.ok(systemAdminService.userList(params));
    }

    // ---------- 用户写操作 ----------

    @PostMapping("/user")
    public ApiResponse<Map<String, Object>> createUser(@RequestBody UserBody body) {
        return ApiResponse.ok(Map.of("id", systemAdminService.createUser(
                body.getUserName(), body.getPassword(), body.getNickName(), body.getGender(),
                body.getPhone(), body.getEmail(), body.getStatus(), body.getRoles()).getId()));
    }

    @PutMapping("/user/{id}")
    public ApiResponse<Map<String, Object>> updateUser(@PathVariable Long id, @RequestBody UserBody body) {
        return ApiResponse.ok(Map.of("id", systemAdminService.updateUser(
                id, body.getUserName(), body.getPassword(), body.getNickName(), body.getGender(),
                body.getPhone(), body.getEmail(), body.getStatus(), body.getRoles()).getId()));
    }

    @DeleteMapping("/user/{id}")
    public ApiResponse<Void> deleteUser(@PathVariable Long id) {
        systemAdminService.deleteUser(id);
        return ApiResponse.ok(null);
    }

    /** 角色新增/编辑请求体 */
    public static class RoleBody {
        private String roleName;
        private String roleCode;
        private String description;
        private Boolean enabled;

        public String getRoleName() { return roleName; }
        public void setRoleName(String roleName) { this.roleName = roleName; }
        public String getRoleCode() { return roleCode; }
        public void setRoleCode(String roleCode) { this.roleCode = roleCode; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    }

    /** 用户新增/编辑请求体（字段对齐前端 UserDialog：userName/password/nickName/gender/phone/email/status/roles） */
    public static class UserBody {
        private String userName;
        private String password;
        private String nickName;
        private String gender;
        private String phone;
        private String email;
        private String status;
        private List<String> roles;

        public String getUserName() { return userName; }
        public void setUserName(String userName) { this.userName = userName; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getNickName() { return nickName; }
        public void setNickName(String nickName) { this.nickName = nickName; }
        public String getGender() { return gender; }
        public void setGender(String gender) { this.gender = gender; }
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public List<String> getRoles() { return roles; }
        public void setRoles(List<String> roles) { this.roles = roles; }
    }

    /** 角色权限保存请求体 */
    public static class PermissionBody {
        private List<String> permissions;

        public List<String> getPermissions() { return permissions; }
        public void setPermissions(List<String> permissions) { this.permissions = permissions; }
    }
}

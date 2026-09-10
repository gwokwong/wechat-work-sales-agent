package com.example.wechatsales.service;

import com.example.wechatsales.domain.SysMenu;
import com.example.wechatsales.domain.SysRole;
import com.example.wechatsales.domain.SysRolePermission;
import com.example.wechatsales.domain.SysUser;
import com.example.wechatsales.exception.BusinessException;
import com.example.wechatsales.exception.NotFoundException;
import com.example.wechatsales.repository.SysMenuRepository;
import com.example.wechatsales.repository.SysRolePermissionRepository;
import com.example.wechatsales.repository.SysRoleRepository;
import com.example.wechatsales.repository.SysUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 系统管理域业务：注册/认证、用户列表、角色管理、菜单管理、角色权限加载与保存。
 * 数据量小，列表过滤/分页在内存完成，保持与现有演示代码一致的轻量风格。
 */
@Service
@RequiredArgsConstructor
public class SystemAdminService {

    /** 兼容现有演示登录账号（仅当用户表为空时回退，seed 后走真实用户表） */
    private static final String DEMO_USERNAME = "admin";
    private static final String DEMO_PASSWORD = "admin123";
    private static final String DEMO_PASSWORD_SHA256 = encodePassword("admin123");

    private final SysUserRepository userRepository;
    private final SysRoleRepository roleRepository;
    private final SysMenuRepository menuRepository;
    private final SysRolePermissionRepository permissionRepository;

    // ---------- 注册 / 认证 ----------

    /** 注册：用户名唯一校验，密码 SHA-256 存储 */
    @Transactional
    public Map<String, Object> register(String userName, String password) {
        if (userRepository.existsByUserName(userName)) {
            throw new BusinessException("用户名已存在: " + userName);
        }
        SysUser user = new SysUser();
        user.setUserName(userName);
        user.setNickName(userName);
        user.setPassword(encodePassword(password));
        user.setStatus("1");
        user.setRoles(new ArrayList<>());
        user.touch();
        SysUser saved = userRepository.save(user);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", saved.getId());
        body.put("userName", saved.getUserName());
        return body;
    }

    /** 认证：用户表非空时查库校验；用户表为空时兼容演示账号 admin/admin123 */
    public boolean authenticate(String userName, String password) {
        if (userRepository.count() == 0) {
            return DEMO_USERNAME.equals(userName) && DEMO_PASSWORD.equals(password);
        }
        return userRepository.findByUserName(userName)
                .map(u -> u.getPassword().equals(encodePassword(password)))
                .orElse(false);
    }

    public static String encodePassword(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    // ---------- 用户列表 ----------

    public Map<String, Object> userList(Map<String, Object> params) {
        String userName = str(params.get("userName"));
        String userGender = str(params.get("userGender"));
        String userPhone = str(params.get("userPhone"));
        String userEmail = str(params.get("userEmail"));
        String status = str(params.get("status"));

        List<Map<String, Object>> records = userRepository.findAll().stream()
                .filter(u -> userName == null || u.getUserName().toLowerCase().contains(userName.toLowerCase()))
                .filter(u -> userGender == null || u.getGender() == null || u.getGender().isEmpty() || u.getGender().equals(userGender))
                .filter(u -> userPhone == null || (u.getPhone() != null && u.getPhone().contains(userPhone)))
                .filter(u -> userEmail == null || (u.getEmail() != null && u.getEmail().toLowerCase().contains(userEmail.toLowerCase())))
                .filter(u -> status == null || status.isBlank() || status.equals(u.getStatus()))
                .map(this::toUserListItem)
                .toList();

        return paginate(records, params);
    }

    private Map<String, Object> toUserListItem(SysUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("avatar", u.getAvatar() == null ? "" : u.getAvatar());
        m.put("status", u.getStatus());
        m.put("userName", u.getUserName());
        m.put("userGender", u.getGender() == null ? "" : u.getGender());
        m.put("nickName", u.getNickName() == null ? "" : u.getNickName());
        m.put("userPhone", u.getPhone() == null ? "" : u.getPhone());
        m.put("userEmail", u.getEmail() == null ? "" : u.getEmail());
        m.put("userRoles", u.getRoles());
        m.put("createBy", u.getCreateBy() == null ? "" : u.getCreateBy());
        m.put("createTime", fmt(u.getCreateTime()));
        m.put("updateBy", u.getUpdateBy() == null ? "" : u.getUpdateBy());
        m.put("updateTime", fmt(u.getUpdateTime()));
        return m;
    }

    // ---------- 用户写操作 ----------

    @Transactional
    public SysUser createUser(String userName, String password, String nickName, String gender,
                              String phone, String email, String status, List<String> roles) {
        if (userName == null || userName.isBlank()) {
            throw new BusinessException("用户名不能为空");
        }
        if (password == null || password.isBlank()) {
            throw new BusinessException("密码不能为空");
        }
        if (userRepository.existsByUserName(userName)) {
            throw new BusinessException("用户名已存在: " + userName);
        }
        SysUser u = new SysUser();
        u.setUserName(userName);
        u.setPassword(encodePassword(password));
        u.setNickName(nickName == null || nickName.isBlank() ? userName : nickName);
        u.setGender(gender);
        u.setPhone(phone);
        u.setEmail(email);
        u.setStatus(status == null || status.isBlank() ? "1" : status);
        u.setRoles(roles == null ? new ArrayList<>() : new ArrayList<>(roles));
        u.touch();
        return userRepository.save(u);
    }

    @Transactional
    public SysUser updateUser(Long id, String userName, String password, String nickName, String gender,
                              String phone, String email, String status, List<String> roles) {
        SysUser u = userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("用户不存在 id=" + id));
        if (userName != null && !userName.isBlank() && !userName.equals(u.getUserName())) {
            if (userRepository.existsByUserName(userName)) {
                throw new BusinessException("用户名已存在: " + userName);
            }
            u.setUserName(userName);
        }
        if (password != null && !password.isBlank()) {
            u.setPassword(encodePassword(password));
        }
        if (nickName != null) {
            u.setNickName(nickName);
        }
        if (gender != null) {
            u.setGender(gender);
        }
        if (phone != null) {
            u.setPhone(phone);
        }
        if (email != null) {
            u.setEmail(email);
        }
        if (status != null && !status.isBlank()) {
            u.setStatus(status);
        }
        if (roles != null) {
            u.setRoles(new ArrayList<>(roles));
        }
        u.touch();
        return userRepository.save(u);
    }

    @Transactional
    public void deleteUser(Long id) {
        if (!userRepository.existsById(id)) {
            throw new NotFoundException("用户不存在 id=" + id);
        }
        // 显式清理用户-角色关联，避免依赖 @ElementCollection 自动清理在线上保留孤儿数据
        userRepository.deleteUserRoles(id);
        userRepository.deleteById(id);
    }

    // ---------- 角色管理 ----------

    public Map<String, Object> roleList(Map<String, Object> params) {
        String roleName = str(params.get("roleName"));
        String roleCode = str(params.get("roleCode"));
        String description = str(params.get("description"));
        String enabledRaw = str(params.get("enabled"));

        List<SysRole> records = roleRepository.findAll().stream()
                .filter(r -> roleName == null || r.getRoleName().toLowerCase().contains(roleName.toLowerCase()))
                .filter(r -> roleCode == null || r.getRoleCode().toLowerCase().contains(roleCode.toLowerCase()))
                .filter(r -> description == null || (r.getDescription() != null && r.getDescription().toLowerCase().contains(description.toLowerCase())))
                .filter(r -> {
                    if (enabledRaw == null || enabledRaw.isBlank()) return true;
                    boolean wantTrue = "true".equalsIgnoreCase(enabledRaw);
                    return Boolean.TRUE.equals(r.getEnabled()) == wantTrue;
                })
                .toList();

        return paginate(records, params);
    }

    @Transactional
    public SysRole createRole(String roleName, String roleCode, String description, Boolean enabled) {
        if (roleRepository.existsByRoleCode(roleCode)) {
            throw new BusinessException("角色编码已存在: " + roleCode);
        }
        SysRole role = new SysRole();
        role.setRoleName(roleName);
        role.setRoleCode(roleCode);
        role.setDescription(description);
        role.setEnabled(enabled == null ? Boolean.TRUE : enabled);
        role.touch();
        return roleRepository.save(role);
    }

    @Transactional
    public SysRole updateRole(Long roleId, String roleName, String roleCode, String description, Boolean enabled) {
        SysRole role = roleRepository.findById(roleId)
                .orElseThrow(() -> new NotFoundException("角色不存在 roleId=" + roleId));
        roleRepository.findByRoleCode(roleCode)
                .filter(other -> !other.getRoleId().equals(roleId))
                .ifPresent(other -> {
                    throw new BusinessException("角色编码已存在: " + roleCode);
                });
        role.setRoleName(roleName);
        role.setRoleCode(roleCode);
        role.setDescription(description);
        role.setEnabled(enabled == null ? Boolean.TRUE : enabled);
        role.touch();
        return roleRepository.save(role);
    }

    @Transactional
    public void deleteRole(Long roleId) {
        if (!roleRepository.existsById(roleId)) {
            throw new NotFoundException("角色不存在 roleId=" + roleId);
        }
        roleRepository.deleteById(roleId);
        permissionRepository.deleteByRoleId(roleId);
    }

    // ---------- 角色权限 ----------

    public List<String> rolePermissions(Long roleId) {
        return permissionRepository.findByRoleId(roleId).stream()
                .map(SysRolePermission::getPermissionKey)
                .toList();
    }

    @Transactional
    public void saveRolePermissions(Long roleId, List<String> permissions) {
        if (!roleRepository.existsById(roleId)) {
            throw new NotFoundException("角色不存在 roleId=" + roleId);
        }
        List<String> keys = permissions == null ? List.of() : permissions.stream().filter(k -> k != null && !k.isBlank()).distinct().toList();
        permissionRepository.deleteByRoleId(roleId);
        for (String key : keys) {
            SysRolePermission p = new SysRolePermission();
            p.setRoleId(roleId);
            p.setPermissionKey(key);
            permissionRepository.save(p);
        }
    }

    // ---------- 菜单管理 ----------

    /** 菜单树（AppRouteRecord 结构，含 meta.authList 权限按钮） */
    public List<Map<String, Object>> menuTree() {
        List<SysMenu> all = menuRepository.findAll();
        Map<Long, List<SysMenu>> byParent = all.stream().collect(Collectors.groupingBy(SysMenu::getParentId));
        return buildMenuChildren(0L, byParent);
    }

    private List<Map<String, Object>> buildMenuChildren(Long parentId, Map<Long, List<SysMenu>> byParent) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<SysMenu> children = byParent.getOrDefault(parentId, List.of()).stream()
                .filter(m -> !Boolean.TRUE.equals(m.getIsAuthButton()) && "menu".equals(m.getMenuType()))
                .sorted(Comparator.comparing(SysMenu::getSort, Comparator.nullsLast(Integer::compareTo)))
                .toList();
        for (SysMenu menu : children) {
            Map<String, Object> node = menuToNode(menu, byParent);
            nodes.add(node);
        }
        return nodes;
    }

    private Map<String, Object> menuToNode(SysMenu menu, Map<Long, List<SysMenu>> byParent) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", menu.getId());
        node.put("parentId", menu.getParentId() == null ? 0L : menu.getParentId());
        node.put("name", menu.getName());
        node.put("path", menu.getPath() == null ? "" : menu.getPath());
        node.put("component", menu.getComponent() == null ? "" : menu.getComponent());

        // 权限按钮：本菜单下 menuType=button 且非独立 isAuthButton 的子节点
        List<Map<String, Object>> authList = byParent.getOrDefault(menu.getId(), List.of()).stream()
                .filter(b -> "button".equals(b.getMenuType()))
                .sorted(Comparator.comparing(SysMenu::getSort, Comparator.nullsLast(Integer::compareTo)))
                .map(b -> {
                    Map<String, Object> auth = new LinkedHashMap<>();
                    auth.put("id", b.getId());
                    auth.put("parentId", b.getParentId() == null ? menu.getId() : b.getParentId());
                    auth.put("authMark", b.getAuthMark());
                    auth.put("title", b.getTitle());
                    return auth;
                })
                .toList();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("title", menu.getTitle() == null ? "" : menu.getTitle());
        meta.put("icon", menu.getIcon() == null ? "" : menu.getIcon());
        meta.put("sort", menu.getSort());
        meta.put("isMenu", Boolean.TRUE.equals(menu.getIsMenu()));
        meta.put("keepAlive", Boolean.TRUE.equals(menu.getKeepAlive()));
        meta.put("isHide", Boolean.TRUE.equals(menu.getIsHide()));
        meta.put("isHideTab", Boolean.TRUE.equals(menu.getIsHideTab()));
        meta.put("isEnable", Boolean.TRUE.equals(menu.getIsEnable()));
        meta.put("link", menu.getLink() == null ? "" : menu.getLink());
        meta.put("isIframe", Boolean.TRUE.equals(menu.getIsIframe()));
        meta.put("showBadge", Boolean.TRUE.equals(menu.getShowBadge()));
        meta.put("showTextBadge", menu.getShowTextBadge() == null ? "" : menu.getShowTextBadge());
        meta.put("fixedTab", Boolean.TRUE.equals(menu.getFixedTab()));
        meta.put("activePath", menu.getActivePath() == null ? "" : menu.getActivePath());
        meta.put("isFullPage", Boolean.TRUE.equals(menu.getIsFullPage()));
        meta.put("roles", menu.getRoles() == null ? List.of() : splitRoles(menu.getRoles()));
        meta.put("isAuthButton", Boolean.TRUE.equals(menu.getIsAuthButton()));
        meta.put("authMark", menu.getAuthMark() == null ? "" : menu.getAuthMark());
        meta.put("authList", authList);
        node.put("meta", meta);

        node.put("children", buildMenuChildren(menu.getId(), byParent));
        return node;
    }

    @Transactional
    public SysMenu createMenu(Map<String, Object> body) {
        SysMenu menu = new SysMenu();
        applyMenuBody(menu, body);
        if (menuRepository.existsByName(menu.getName())) {
            throw new BusinessException("菜单标识已存在: " + menu.getName());
        }
        menu.touch();
        return menuRepository.save(menu);
    }

    @Transactional
    public SysMenu updateMenu(Long menuId, Map<String, Object> body) {
        SysMenu menu = menuRepository.findById(menuId)
                .orElseThrow(() -> new NotFoundException("菜单不存在 id=" + menuId));
        applyMenuBody(menu, body);
        menu.touch();
        return menuRepository.save(menu);
    }

    @Transactional
    public void deleteMenu(Long menuId) {
        if (!menuRepository.existsById(menuId)) {
            throw new NotFoundException("菜单不存在 id=" + menuId);
        }
        // 递归删除子节点（含权限按钮）
        deleteMenuRecursively(menuId);
    }

    private void deleteMenuRecursively(Long id) {
        List<SysMenu> children = menuRepository.findByParentIdOrderBySortAsc(id);
        for (SysMenu child : children) {
            deleteMenuRecursively(child.getId());
        }
        menuRepository.deleteById(id);
    }

    private void applyMenuBody(SysMenu menu, Map<String, Object> body) {
        // 前端菜单表单字段映射：name=菜单名称->title，label=权限标识->name，path=路由地址->path
        String name = str(body.get("name"));
        String label = str(body.get("label"));
        String menuType = str(body.get("menuType"));
        boolean isButton = "button".equals(menuType);

        menu.setName(isButton ? (label == null ? name : label) : (label == null ? name : label));
        menu.setTitle(name);
        menu.setPath(str(body.get("path")));
        menu.setComponent(str(body.get("component")));
        menu.setIcon(str(body.get("icon")));
        menu.setSort(intVal(body.get("sort"), 1));
        menu.setMenuType(isButton ? "button" : "menu");

        if (isButton) {
            menu.setAuthMark(str(body.get("authLabel")));
            menu.setAuthSort(intVal(body.get("authSort"), 1));
            menu.setIsAuthButton(true);
        } else {
            menu.setAuthMark(null);
            menu.setIsAuthButton(false);
        }

        menu.setIsEnable(boolVal(body.get("isEnable"), true));
        menu.setIsMenu(boolVal(body.get("isMenu"), true));
        menu.setKeepAlive(boolVal(body.get("keepAlive"), false));
        menu.setIsHide(boolVal(body.get("isHide"), false));
        menu.setIsHideTab(boolVal(body.get("isHideTab"), false));
        menu.setIsIframe(boolVal(body.get("isIframe"), false));
        menu.setLink(str(body.get("link")));
        menu.setShowBadge(boolVal(body.get("showBadge"), false));
        menu.setShowTextBadge(str(body.get("showTextBadge")));
        menu.setFixedTab(boolVal(body.get("fixedTab"), false));
        menu.setActivePath(str(body.get("activePath")));
        menu.setIsFullPage(boolVal(body.get("isFullPage"), false));

        Object roles = body.get("roles");
        menu.setRoles(roles == null ? null : String.join(",", toStringList(roles)));

        // 父子关系：从 body 读取 parentId 落库；新增按钮必须挂到具体父菜单
        Object rawParentId = body.get("parentId");
        long parentId = rawParentId == null ? 0L : longVal(rawParentId, 0L);
        if (parentId < 0) {
            parentId = 0L;
        }
        menu.setParentId(parentId);
        if (isButton && menu.getId() == null && parentId == 0L) {
            throw new BusinessException("权限按钮必须选择所属父级菜单");
        }
    }

    // ---------- 工具 ----------

    private <T> Map<String, Object> paginate(List<T> all, Map<String, Object> params) {
        int current = intVal(params.get("current"), 1);
        int size = intVal(params.get("size"), 10);
        if (current < 1) current = 1;
        if (size < 1) size = 10;
        int total = all.size();
        int from = Math.min((current - 1) * size, total);
        int to = Math.min(from + size, total);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("records", new ArrayList<>(all.subList(from, to)));
        body.put("current", current);
        body.put("size", size);
        body.put("total", total);
        return body;
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static int intVal(Object o, int fallback) {
        if (o == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long longVal(Object o, long fallback) {
        if (o == null) return fallback;
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean boolVal(Object o, boolean fallback) {
        if (o == null) return fallback;
        String s = String.valueOf(o).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    private static List<String> toStringList(Object o) {
        if (o == null) return List.of();
        if (o instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of(String.valueOf(o));
    }

    private static List<String> splitRoles(String roles) {
        return java.util.Arrays.stream(roles.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String fmt(java.time.LocalDateTime t) {
        return t == null ? null : t.toString().replace('T', ' ');
    }
}

class SysMenuAuthSortHolder {
}

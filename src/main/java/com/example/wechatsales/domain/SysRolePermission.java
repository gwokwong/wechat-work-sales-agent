package com.example.wechatsales.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 角色-菜单权限关联（系统管理域）。
 * permission_key 为前端权限树的 node-key：菜单 name 或 "${menuName}_${authMark}"，
 * 后端仅作不透明字符串保存/回传，由前端树负责语义。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "sys_role_permission")
public class SysRolePermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Column(name = "permission_key", nullable = false, length = 200)
    private String permissionKey;
}

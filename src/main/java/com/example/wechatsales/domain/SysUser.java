package com.example.wechatsales.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 系统用户（系统管理域；注册/登录/用户列表共用） */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "sys_user")
public class SysUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_name", nullable = false, unique = true, length = 50)
    private String userName;

    /** 密码 SHA-256（16 进制小写），演示项目不做加盐加密 */
    @Column(nullable = false, length = 128)
    private String password;

    @Column(length = 50)
    private String nickName;

    @Column(length = 8)
    private String gender;

    @Column(length = 20)
    private String phone;

    @Column(length = 100)
    private String email;

    @Column(length = 255)
    private String avatar;

    /** 状态：1在线 2离线 3异常 4注销 */
    @Column(length = 8)
    private String status = "1";

    /** 角色编码集合（userRoles） */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sys_user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role_code", length = 50)
    private List<String> roles = new ArrayList<>();

    @Column(name = "create_by", length = 50)
    private String createBy;

    @Column(name = "update_by", length = 50)
    private String updateBy;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    public void touch() {
        LocalDateTime now = LocalDateTime.now();
        if (createTime == null) {
            createTime = now;
            createBy = "system";
        }
        updateTime = now;
        updateBy = "system";
    }
}

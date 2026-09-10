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

import java.time.LocalDateTime;

/**
 * 系统菜单/权限按钮（系统管理域）。
 * menu_type=menu 为菜单/目录，menu_type=button 为挂在父菜单下的权限按钮；
 * 权限按钮通过 auth_mark + is_auth_button 表达（对应前端 meta.authMark / meta.isAuthButton）。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "sys_menu")
public class SysMenu {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 父菜单 id，顶级为 0 */
    @Column(name = "parent_id", nullable = false)
    private Long parentId = 0L;

    /** 前端路由 name，需全局唯一 */
    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(length = 200)
    private String path;

    @Column(length = 200)
    private String component;

    /** meta.title */
    @Column(length = 100)
    private String title;

    /** meta.icon */
    @Column(length = 100)
    private String icon;

    /** 菜单排序，越小越靠前 */
    @Column(nullable = false)
    private Integer sort = 1;

    /** menu / button */
    @Column(name = "menu_type", length = 10)
    private String menuType = "menu";

    /** 权限按钮标识（meta.authMark，仅 button 使用） */
    @Column(name = "auth_mark", length = 100)
    private String authMark;

    /** 权限按钮排序（仅 button 使用） */
    @Column(name = "auth_sort")
    private Integer authSort = 1;

    /** 是否权限按钮（meta.isAuthButton） */
    @Column(name = "is_auth_button", nullable = false)
    private Boolean isAuthButton = false;

    @Column(name = "is_enable", nullable = false)
    private Boolean isEnable = true;

    @Column(name = "is_menu", nullable = false)
    private Boolean isMenu = true;

    @Column(name = "keep_alive", nullable = false)
    private Boolean keepAlive = false;

    @Column(name = "is_hide", nullable = false)
    private Boolean isHide = false;

    @Column(name = "is_hide_tab", nullable = false)
    private Boolean isHideTab = false;

    @Column(name = "is_iframe", nullable = false)
    private Boolean isIframe = false;

    @Column(length = 300)
    private String link;

    @Column(name = "show_badge", nullable = false)
    private Boolean showBadge = false;

    @Column(name = "show_text_badge", length = 50)
    private String showTextBadge;

    @Column(name = "fixed_tab", nullable = false)
    private Boolean fixedTab = false;

    @Column(name = "active_path", length = 200)
    private String activePath;

    @Column(name = "is_full_page", nullable = false)
    private Boolean isFullPage = false;

    /** 前端权限模式角色标识，逗号分隔 */
    @Column(name = "roles", length = 200)
    private String roles;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    public void touch() {
        LocalDateTime now = LocalDateTime.now();
        if (createTime == null) {
            createTime = now;
        }
        updateTime = now;
    }
}

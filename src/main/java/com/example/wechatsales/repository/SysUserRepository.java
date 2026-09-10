package com.example.wechatsales.repository;

import com.example.wechatsales.domain.SysUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SysUserRepository extends JpaRepository<SysUser, Long> {

    Optional<SysUser> findByUserName(String userName);

    boolean existsByUserName(String userName);

    /**
     * 显式删除用户-角色关联（sys_user_roles）记录。
     * <p>兜底在线上 MySQL（ddl-auto=none）下即使 Hibernate 未自动清理
     * {@link SysUser#getRoles} 的 @ElementCollection 集合也会残留孤儿数据。</p>
     */
    @Modifying
    @Query(value = "DELETE FROM sys_user_roles WHERE user_id = :userId", nativeQuery = true)
    void deleteUserRoles(@Param("userId") Long userId);
}

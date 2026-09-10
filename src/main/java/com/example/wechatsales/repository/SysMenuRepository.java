package com.example.wechatsales.repository;

import com.example.wechatsales.domain.SysMenu;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SysMenuRepository extends JpaRepository<SysMenu, Long> {

    List<SysMenu> findByParentIdOrderBySortAsc(Long parentId);

    boolean existsByName(String name);
}

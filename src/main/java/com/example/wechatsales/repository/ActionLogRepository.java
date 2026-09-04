package com.example.wechatsales.repository;

import com.example.wechatsales.domain.ActionLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ActionLogRepository extends JpaRepository<ActionLog, Long> {

    List<ActionLog> findByOrderByCreatedAtDesc();

    List<ActionLog> findByContactIdOrderByCreatedAtDesc(Long contactId);
}

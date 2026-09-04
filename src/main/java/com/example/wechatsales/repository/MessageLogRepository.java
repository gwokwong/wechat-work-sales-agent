package com.example.wechatsales.repository;

import com.example.wechatsales.domain.MessageLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageLogRepository extends JpaRepository<MessageLog, Long> {

    boolean existsByMsgId(String msgId);

    List<MessageLog> findByContactIdOrderByCreatedAtDesc(Long contactId);

    List<MessageLog> findTop10ByContactIdAndDirectionOrderByCreatedAtDesc(Long contactId, String direction);
}

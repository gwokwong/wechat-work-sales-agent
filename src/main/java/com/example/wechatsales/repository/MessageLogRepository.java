package com.example.wechatsales.repository;

import com.example.wechatsales.domain.MessageLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MessageLogRepository extends JpaRepository<MessageLog, Long> {

    boolean existsByMsgId(String msgId);

    List<MessageLog> findByContactIdOrderByCreatedAtDesc(Long contactId);

    List<MessageLog> findTop10ByContactIdAndDirectionOrderByCreatedAtDesc(Long contactId, String direction);

    /** 该客户最近一条外发消息（无则 empty）——策略级最小发送间隔校验用 */
    Optional<MessageLog> findTop1ByContactIdAndDirectionOrderByCreatedAtDesc(Long contactId, String direction);
}

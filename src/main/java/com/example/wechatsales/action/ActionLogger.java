package com.example.wechatsales.action;

import com.example.wechatsales.domain.ActionLog;
import com.example.wechatsales.repository.ActionLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/** 动作日志：所有关键动作（草稿/审批/报价/发送/阶段跃迁/合规阻断）写审计流 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActionLogger {

    private final ActionLogRepository actionLogRepository;

    public void log(Long contactId, String actionType, String detail) {
        ActionLog entry = new ActionLog();
        entry.setContactId(contactId);
        entry.setActionType(actionType);
        entry.setDetail(detail);
        entry.touch();
        actionLogRepository.save(entry);
        log.info("[ActionLog] contactId={} action={} detail={}", contactId, actionType, truncate(detail));
    }

    public List<ActionLog> recent() {
        return actionLogRepository.findByOrderByCreatedAtDesc();
    }

    public List<ActionLog> byContact(Long contactId) {
        return actionLogRepository.findByContactIdOrderByCreatedAtDesc(contactId);
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}

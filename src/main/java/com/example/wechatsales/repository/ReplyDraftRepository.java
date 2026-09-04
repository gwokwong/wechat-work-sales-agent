package com.example.wechatsales.repository;

import com.example.wechatsales.domain.ReplyDraft;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReplyDraftRepository extends JpaRepository<ReplyDraft, Long> {

    List<ReplyDraft> findByStatusOrderByCreatedAtAsc(String status);

    List<ReplyDraft> findAllByOrderByCreatedAtDesc();

    List<ReplyDraft> findByContactIdOrderByCreatedAtDesc(Long contactId);
}

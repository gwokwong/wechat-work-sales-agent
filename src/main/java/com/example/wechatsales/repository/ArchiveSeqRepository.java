package com.example.wechatsales.repository;

import com.example.wechatsales.domain.ArchiveSeqState;
import org.springframework.data.jpa.repository.JpaRepository;

/** 会话存档 seq 游标持久化（单行：id=1） */
public interface ArchiveSeqRepository extends JpaRepository<ArchiveSeqState, Long> {
}

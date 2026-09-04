package com.example.wechatsales.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 会话存档拉取游标（seq）持久化状态。
 *
 * <p>企微 getchatdata 必须"以回包中最大 next_seq 作为下次拉取起点"，seq 游标必须
 * 持久化，防止漏拉/重拉。本表单行记录（id=1），由 {@code WeComArchivePuller} 每次
 * 拉取后更新。MySQL 中对应 schema.sql 的 {@code archive_seq} 表。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "archive_seq")
public class ArchiveSeqState {

    /** 固定主键 1（单企业单实例即可用单行） */
    @Id
    private Long id = 1L;

    /** 下一次拉取起点（已成功处理到的最大 seq）；列名避免使用 MySQL 保留字 */
    @Column(name = "seq_cursor", nullable = false)
    private Long seq = 0L;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public void advance(Long nextSeq) {
        this.seq = nextSeq;
        this.updatedAt = LocalDateTime.now();
    }
}

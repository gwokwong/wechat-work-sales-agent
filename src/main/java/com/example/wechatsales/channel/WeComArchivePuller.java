package com.example.wechatsales.channel;

import com.example.wechatsales.config.AppProperties;
import com.example.wechatsales.crypto.ArchiveDecryptor;
import com.example.wechatsales.domain.ArchiveSeqState;
import com.example.wechatsales.domain.Message;
import com.example.wechatsales.repository.ArchiveSeqRepository;
import com.example.wechatsales.repository.MessageLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 会话存档定时增量拉取器：按持久化 seq 拉取 → RSA/AES 解密 → 文本映射 →
 * msgId 幂等 → publish MessageBus。
 *
 * <p>硬约束（与 M0 长期记忆一致）：以回包中最大 {@code next_seq} 作为下次起点，禁止
 * 手动 seq+1；seq 游标持久化在 {@code archive_seq} 表；单条解密失败不中断整批，避免
 * 阻塞游标推进（失败明细日志留存，真实联调期建议人工比对补拉）。</p>
 *
 * <p>仅当 {@code app.wecom.enabled=true} 才创建（无真实配置时默认不启用、启动不报错）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.wecom", name = "enabled", havingValue = "true")
public class WeComArchivePuller {

    private final WeComApiClient weComApiClient;
    private final ArchiveSeqRepository archiveSeqRepository;
    private final MessageLogRepository messageLogRepository;
    private final MessageBus messageBus;
    private final ArchiveMessageMapper archiveMessageMapper;
    private final AppProperties appProperties;

    private final AtomicBoolean warnedOnce = new AtomicBoolean(false);

    /** 定时拉取（开关 app.wecom.archive-pull-enabled；无凭据时静默降级） */
    @Scheduled(initialDelayString = "${app.wecom.archive-pull-initial-delay-ms:10000}",
            fixedDelayString = "${app.wecom.archive-pull-fixed-delay-ms:60000}")
    public void scheduledPull() {
        if (!appProperties.getWecom().isArchivePullEnabled()) {
            return;
        }
        if (!weComApiClient.isConfigured()) {
            if (warnedOnce.compareAndSet(false, true)) {
                log.warn("[ArchivePuller] 企微会话存档凭据未配置完整（corpId/session-secret/私钥），"
                        + "定时拉取已跳过；配置 application-wecom.yml 后重启生效");
            }
            return;
        }
        pullOnce();
    }

    /** 手动触发一次拉取（供联调 REST / 管理端扩展），返回处理统计 */
    public PullSummary pullOnce() {
        AppProperties.Wecom wecom = appProperties.getWecom();
        long seq = loadOrInitSeq();
        WeComApiClient.ChatDataResult result = weComApiClient.pullChatData(seq);

        int published = 0;
        int skippedDup = 0;
        int skippedUnmapped = 0;
        int errors = 0;
        for (ArchiveChatRecord record : result.records()) {
            try {
                // 1) 解密（RSA 解会话密钥 + AES-256-CBC 解消息体）
                String plainJson = ArchiveDecryptor.decryptChatMsg(
                        record.encryptRandomKey(), record.encryptChatMsg(),
                        wecom.getSessionArchivePrivateKey());
                // 2) 映射为领域 Message（文本 + 可定位外部客户）
                Optional<Message> mapped = archiveMessageMapper.mapToMessage(plainJson);
                if (mapped.isEmpty()) {
                    skippedUnmapped++;
                    continue;
                }
                Message msg = mapped.get();
                // 3) msgId 幂等：已落 message_log 的消息不再投递
                if (messageLogRepository.existsByMsgId(msg.getMsgId())) {
                    skippedDup++;
                    continue;
                }
                // 4) 投递总线，交由编排器建档/落库/生成草稿
                messageBus.publish(msg);
                published++;
            } catch (Exception e) {
                errors++;
                log.error("[ArchivePuller] 单条存档消息处理失败 seq={} msgid={} err={}",
                        record.seq(), record.msgid(), e.getMessage(), e);
            }
        }

        // 推进 seq 游标（以企微回包 next_seq 为准）
        long nextSeq = result.nextSeq();
        if (nextSeq > seq) {
            ArchiveSeqState state = archiveSeqRepository.findById(1L).orElseGet(ArchiveSeqState::new);
            state.setId(1L);
            state.advance(nextSeq);
            archiveSeqRepository.save(state);
        }
        log.info("[ArchivePuller] 拉取完成 seq {}→{} records={} published={} dup={} unmapped={} errors={}",
                seq, nextSeq, result.records().size(), published, skippedDup, skippedUnmapped, errors);
        return new PullSummary(seq, nextSeq, result.records().size(), published, skippedDup, skippedUnmapped, errors);
    }

    private long loadOrInitSeq() {
        ArchiveSeqState state = archiveSeqRepository.findById(1L).orElse(null);
        if (state == null) {
            state = new ArchiveSeqState();
            state.setId(1L);
            state.setSeq(0L);
            state.setUpdatedAt(LocalDateTime.now());
            archiveSeqRepository.save(state);
            return 0L;
        }
        return state.getSeq() == null ? 0L : state.getSeq();
    }

    /** 单次拉取统计 */
    public record PullSummary(long fromSeq, long toSeq, int total, int published,
                              int skippedDup, int skippedUnmapped, int errors) {
    }
}

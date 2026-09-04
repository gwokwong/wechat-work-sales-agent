package com.example.wechatsales.strategy;

import com.example.wechatsales.action.ActionLogger;
import com.example.wechatsales.channel.OutboundSender;
import com.example.wechatsales.channel.SendResult;
import com.example.wechatsales.domain.ReplyDraft;
import com.example.wechatsales.exception.BusinessException;
import com.example.wechatsales.exception.NotFoundException;
import com.example.wechatsales.repository.ReplyDraftRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 人工闸门服务：待审批话术队列 —— 查看 / 批准发送 / 驳回 / 自动发送。
 *
 * <p>审批模式（app.approval-mode）：
 * <ul>
 *   <li>MANUAL（演示默认）：编排器只生成 {@code PENDING} 草稿，必须由人工 REST 审批后才发送；</li>
 *   <li>AUTO（仅测试）：草稿自动批准并立即发送，绕过人工闸门。</li>
 * </ul></p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DraftApprovalService {

    private final ReplyDraftRepository draftRepository;
    private final OutboundSender outboundSender;
    private final ActionLogger actionLogger;

    public List<ReplyDraft> pending() {
        return draftRepository.findByStatusOrderByCreatedAtAsc(ReplyDraft.STATUS_PENDING);
    }

    public List<ReplyDraft> recent() {
        return draftRepository.findAllByOrderByCreatedAtDesc();
    }

    /** 人工批准并发送：PENDING/APPROVED 状态可直接发送；BLOCKED 需先人工编辑（当前只允许拒绝） */
    @Transactional
    public ReplyDraft approveAndSend(Long draftId) {
        ReplyDraft draft = requireDraft(draftId);
        if (ReplyDraft.STATUS_SENT.equals(draft.getStatus())) {
            throw new BusinessException("该话术已发送，不能重复发送 draftId=" + draftId);
        }
        if (ReplyDraft.STATUS_REJECTED.equals(draft.getStatus())) {
            throw new BusinessException("该话术已驳回，不能发送；请重新生成 draftId=" + draftId);
        }
        if (ReplyDraft.STATUS_BLOCKED.equals(draft.getStatus())) {
            throw new BusinessException("该话术被合规校验阻断（" + draft.getBlockedReason()
                    + "），需人工改写后重新走流程 draftId=" + draftId);
        }

        draft.approve();
        draftRepository.save(draft);
        actionLogger.log(draft.getContactId(), "DRAFT_APPROVED",
                "draftId=" + draftId + " strategy=" + draft.getStrategyName());

        SendResult result = outboundSender.sendByContactId(draft.getContactId(), draft.getContent());
        if (!result.success()) {
            actionLogger.log(draft.getContactId(), "DRAFT_SEND_FAILED",
                    "draftId=" + draftId + " error=" + result.error());
            throw new BusinessException("发送失败：" + result.error());
        }
        draft.markSent();
        draftRepository.save(draft);
        return draft;
    }

    /** 驳回（人工拒绝） */
    @Transactional
    public ReplyDraft reject(Long draftId, String reason) {
        ReplyDraft draft = requireDraft(draftId);
        if (ReplyDraft.STATUS_SENT.equals(draft.getStatus())) {
            throw new BusinessException("该话术已发送，不能驳回 draftId=" + draftId);
        }
        if (ReplyDraft.STATUS_BLOCKED.equals(draft.getStatus())) {
            // 合规阻断视为已拒绝，避免重复操作
            throw new BusinessException("该话术已被合规校验阻断，无需再次驳回 draftId=" + draftId);
        }
        draft.reject();
        draft.setBlockedReason(reason);
        draftRepository.save(draft);
        actionLogger.log(draft.getContactId(), "DRAFT_REJECTED",
                "draftId=" + draftId + " reason=" + (reason == null ? "" : reason));
        return draft;
    }

    /** 自动发送：供 AUTO 审批模式调用（同步写草稿状态，保证审计完整） */
    @Transactional
    public ReplyDraft autoApproveAndSend(ReplyDraft draft) {
        ReplyDraft saved = draftRepository.save(draft);
        return approveAndSend(saved.getId());
    }

    private ReplyDraft requireDraft(Long draftId) {
        return draftRepository.findById(draftId)
                .orElseThrow(() -> new NotFoundException("话术草稿不存在 draftId=" + draftId));
    }
}

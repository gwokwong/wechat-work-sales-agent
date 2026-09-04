package com.example.wechatsales.strategy;

import com.example.wechatsales.action.ActionLogger;
import com.example.wechatsales.channel.OutboundSender;
import com.example.wechatsales.channel.SendResult;
import com.example.wechatsales.domain.ReplyDraft;
import com.example.wechatsales.exception.BusinessException;
import com.example.wechatsales.exception.NotFoundException;
import com.example.wechatsales.repository.ReplyDraftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 人工审批闸门服务单测：批准发送成功流转 / 非法状态拒绝 / 发送失败回滚 / 驳回 / 草稿不存在。
 */
@ExtendWith(MockitoExtension.class)
class DraftApprovalServiceTest {

    @Mock
    private ReplyDraftRepository draftRepository;
    @Mock
    private OutboundSender outboundSender;
    @Mock
    private ActionLogger actionLogger;

    private DraftApprovalService service;

    @BeforeEach
    void setUp() {
        service = new DraftApprovalService(draftRepository, outboundSender, actionLogger);
    }

    @Test
    void approveAndSendSuccessPersistsSent() {
        ReplyDraft draft = draft(5L, 1L, ReplyDraft.STATUS_PENDING, "您好，这是正式方案报价。");
        when(draftRepository.findById(5L)).thenReturn(Optional.of(draft));
        when(outboundSender.sendByContactId(1L, draft.getContent())).thenReturn(SendResult.ok("mock-msg-1"));

        ReplyDraft result = service.approveAndSend(5L);

        assertSame(draft, result);
        assertEquals(ReplyDraft.STATUS_SENT, result.getStatus());
        // approve() 先落一次 APPROVED，markSent() 后再落一次 SENT
        verify(draftRepository, times(2)).save(draft);
        verify(outboundSender).sendByContactId(1L, draft.getContent());
        verify(actionLogger).log(eq(1L), eq("DRAFT_APPROVED"), anyString());
    }

    @Test
    void approveRejectsSentRejectedAndBlockedStates() {
        ReplyDraft sent = draft(11L, 1L, ReplyDraft.STATUS_SENT, "hi");
        when(draftRepository.findById(11L)).thenReturn(Optional.of(sent));
        BusinessException e1 = assertThrows(BusinessException.class, () -> service.approveAndSend(11L));
        assertTrue(e1.getMessage().contains("已发送"));

        ReplyDraft rejected = draft(12L, 1L, ReplyDraft.STATUS_REJECTED, "hi");
        when(draftRepository.findById(12L)).thenReturn(Optional.of(rejected));
        BusinessException e2 = assertThrows(BusinessException.class, () -> service.approveAndSend(12L));
        assertTrue(e2.getMessage().contains("已驳回"));

        ReplyDraft blocked = draft(13L, 1L, ReplyDraft.STATUS_BLOCKED, "hi");
        blocked.setBlockedReason("命中敏感词「保证」");
        when(draftRepository.findById(13L)).thenReturn(Optional.of(blocked));
        BusinessException e3 = assertThrows(BusinessException.class, () -> service.approveAndSend(13L));
        assertTrue(e3.getMessage().contains("合规校验阻断"));
        verify(outboundSender, never()).sendByContactId(any(), anyString());
    }

    @Test
    void rejectFlowAndSendFailureAndNotFound() {
        // 驳回成功：REJECTED + 记录原因 + 审计
        ReplyDraft draft = draft(7L, 3L, ReplyDraft.STATUS_PENDING, "hi");
        when(draftRepository.findById(7L)).thenReturn(Optional.of(draft));
        ReplyDraft result = service.reject(7L, "话术太生硬，请重写");
        assertEquals(ReplyDraft.STATUS_REJECTED, result.getStatus());
        assertEquals("话术太生硬，请重写", result.getBlockedReason());
        verify(actionLogger).log(eq(3L), eq("DRAFT_REJECTED"), anyString());

        // 草稿不存在
        when(draftRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.reject(99L, null));

        // 发送失败：抛业务异常并记录失败审计
        ReplyDraft failDraft = draft(8L, 4L, ReplyDraft.STATUS_PENDING, "正式方案请查收");
        when(draftRepository.findById(8L)).thenReturn(Optional.of(failDraft));
        when(outboundSender.sendByContactId(4L, failDraft.getContent()))
                .thenReturn(SendResult.fail("mock 通道未配置"));
        BusinessException e = assertThrows(BusinessException.class, () -> service.approveAndSend(8L));
        assertTrue(e.getMessage().contains("发送失败"));
        verify(actionLogger).log(eq(4L), eq("DRAFT_SEND_FAILED"), anyString());
    }

    private ReplyDraft draft(Long id, Long contactId, String status, String content) {
        ReplyDraft d = new ReplyDraft();
        d.setId(id);
        d.setContactId(contactId);
        d.setStatus(status);
        d.setContent(content);
        d.setStage("PROPOSAL");
        d.setStrategyName("方案报价");
        d.setActionType("SEND_TEXT");
        d.touch();
        return d;
    }
}

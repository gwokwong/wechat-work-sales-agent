package com.example.wechatsales.rest;

import com.example.wechatsales.action.ActionLogger;
import com.example.wechatsales.channel.MockChannel;
import com.example.wechatsales.channel.OutboundSender;
import com.example.wechatsales.channel.SendResult;
import com.example.wechatsales.context.ContextAssemblyService;
import com.example.wechatsales.context.ContactContext;
import com.example.wechatsales.domain.*;
import com.example.wechatsales.repository.*;
import com.example.wechatsales.stage.DealService;
import com.example.wechatsales.stage.StageMachine;
import com.example.wechatsales.strategy.DraftApprovalService;
import com.example.wechatsales.strategy.StrategyConfig;
import com.example.wechatsales.strategy.StrategyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin 演示管理端 REST 接口：客户列表 / 上下文 / 阶段 / 待审批话术 /
 * 手动发送 / 演示注入 / 动作日志 / 策略配置，覆盖 M0 演示所需全部操作。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AdminController {

    private final ContactRepository contactRepository;
    private final CustomerProfileRepository profileRepository;
    private final MessageLogRepository messageLogRepository;
    private final DealRepository dealRepository;
    private final QuoteRequestRepository quoteRequestRepository;
    private final ReplyDraftRepository draftRepository;

    private final ContextAssemblyService contextAssemblyService;
    private final DealService dealService;
    private final StageMachine stageMachine;
    private final DraftApprovalService draftApprovalService;
    private final OutboundSender outboundSender;
    private final ActionLogger actionLogger;
    private final StrategyService strategyService;
    private final MockChannel mockChannel;

    // ---------- 客户 ----------

    @GetMapping("/customers")
    public ApiResponse<List<Contact>> customers() {
        return ApiResponse.ok(contactRepository.findAll());
    }

    /** 客户完整上下文（联系人 + 画像 + 商机 + 最近对话摘要），便于人工判断 */
    @GetMapping("/customers/{contactId}/context")
    public ApiResponse<Map<String, Object>> context(@PathVariable Long contactId) {
        ContactContext ctx = contextAssemblyService.assemble(contactId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contact", ctx.getContact());
        body.put("profile", ctx.getProfile());
        body.put("deal", ctx.getDeal());
        body.put("recentCustomerMessages", ctx.getRecentCustomerMessages());
        body.put("summary", contextAssemblyService.renderSummary(ctx));
        return ApiResponse.ok(body);
    }

    @GetMapping("/customers/{contactId}/messages")
    public ApiResponse<List<MessageLog>> customerMessages(@PathVariable Long contactId) {
        return ApiResponse.ok(messageLogRepository.findByContactIdOrderByCreatedAtDesc(contactId));
    }

    // ---------- 商机 / 阶段 ----------

    @GetMapping("/deals")
    public ApiResponse<List<Deal>> deals() {
        return ApiResponse.ok(dealRepository.findAll());
    }

    /** 手动推进状态机（演示管理端可强制跃迁到目标阶段，如把流程推回指定阶段演示） */
    @PostMapping("/deals/{dealId}/transition")
    public ApiResponse<Deal> transition(@PathVariable Long dealId, @RequestBody StageRequest req) {
        Deal deal = dealRepository.findById(dealId)
                .orElseThrow(() -> new com.example.wechatsales.exception.NotFoundException("商机不存在 dealId=" + dealId));
        SalesStage to = SalesStage.valueOf(req.stage());
        return ApiResponse.ok("ok", stageMachine.transition(deal, to));
    }

    // ---------- 待审批话术 / 人工闸门 ----------

    @GetMapping("/drafts/pending")
    public ApiResponse<List<ReplyDraft>> pendingDrafts() {
        return ApiResponse.ok(draftApprovalService.pending());
    }

    @GetMapping("/drafts")
    public ApiResponse<List<ReplyDraft>> allDrafts() {
        return ApiResponse.ok(draftApprovalService.recent());
    }

    /** 人工确认并发送 */
    @PostMapping("/drafts/{draftId}/approve")
    public ApiResponse<ReplyDraft> approve(@PathVariable Long draftId) {
        return ApiResponse.ok("已批准并发送", draftApprovalService.approveAndSend(draftId));
    }

    /** 人工驳回 */
    @PostMapping("/drafts/{draftId}/reject")
    public ApiResponse<ReplyDraft> reject(@PathVariable Long draftId, @RequestBody(required = false) RejectRequest req) {
        String reason = req == null ? null : req.reason();
        return ApiResponse.ok("已驳回", draftApprovalService.reject(draftId, reason));
    }

    // ---------- 手动发送（人工直接外发任意内容） ----------

    @PostMapping("/customers/{contactId}/send")
    public ApiResponse<SendResult> manualSend(@PathVariable Long contactId,
                                              @Valid @RequestBody SendRequest req) {
        SendResult result = outboundSender.sendByContactId(contactId, req.content());
        if (!result.success()) {
            return ApiResponse.fail(500, "发送失败: " + result.error());
        }
        return ApiResponse.ok("发送成功", result);
    }

    // ---------- 演示流水 ----------

    /** 手动注入一条客户消息（模拟企微来消息，驱动完整链路） */
    @PostMapping("/demo/inject")
    public ApiResponse<String> inject(@Valid @RequestBody InjectRequest req) {
        mockChannel.inject(req.externalUserId(), req.content());
        return ApiResponse.ok("已注入模拟消息并进入处理流水线", null);
    }

    /** 播放下一条预置剧本 */
    @PostMapping("/demo/inject-next")
    public ApiResponse<Map<String, Object>> injectNext() {
        boolean ok = mockChannel.injectNextPreset();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("injected", ok);
        body.put("remaining", mockChannel.remainingScripts());
        return ApiResponse.ok(body);
    }

    @GetMapping("/demo/status")
    public ApiResponse<Map<String, Object>> demoStatus() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("remainingScripts", mockChannel.remainingScripts());
        body.put("script", mockChannel.currentScripts());
        body.put("customerCount", contactRepository.count());
        body.put("pendingDrafts", draftRepository.findByStatusOrderByCreatedAtAsc(ReplyDraft.STATUS_PENDING).size());
        body.put("blockedDrafts", draftRepository.findByStatusOrderByCreatedAtAsc(ReplyDraft.STATUS_BLOCKED).size());
        body.put("messageCount", messageLogRepository.count());
        body.put("dealCount", dealRepository.count());
        return ApiResponse.ok(body);
    }

    // ---------- 审计与策略 ----------

    @GetMapping("/logs")
    public ApiResponse<List<ActionLog>> logs(@RequestParam(required = false) Long contactId) {
        if (contactId != null) {
            return ApiResponse.ok(actionLogger.byContact(contactId));
        }
        return ApiResponse.ok(actionLogger.recent());
    }

    @GetMapping("/strategies")
    public ApiResponse<List<StrategyConfig>> strategies() {
        return ApiResponse.ok(strategyService.listAll());
    }

    // ---------- 请求体 ----------

    public record StageRequest(@NotBlank String stage) {
    }

    public record RejectRequest(String reason) {
    }

    public record SendRequest(@NotBlank String content) {
    }

    public record InjectRequest(@NotBlank String externalUserId, @NotBlank String content) {
    }
}

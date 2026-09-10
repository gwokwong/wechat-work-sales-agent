package com.example.wechatsales.strategy;

import com.example.wechatsales.action.ActionLogger;
import com.example.wechatsales.action.QuoteResult;
import com.example.wechatsales.action.QuoteService;
import com.example.wechatsales.channel.MessageBus;
import com.example.wechatsales.channel.MessageListener;
import com.example.wechatsales.config.AppProperties;
import com.example.wechatsales.context.ContextAssemblyService;
import com.example.wechatsales.context.ContactContext;
import com.example.wechatsales.context.CustomerProfileService;
import com.example.wechatsales.domain.*;
import com.example.wechatsales.repository.MessageLogRepository;
import com.example.wechatsales.repository.ReplyDraftRepository;
import com.example.wechatsales.stage.DealService;
import com.example.wechatsales.stage.StageClassifier;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 销售编排器（Sales Agent 核心中枢）：消费 MessageBus 上的客户消息，串联完整链路：
 *
 * <pre>
 * 收消息(msgId幂等) → 建档(Contact) → 落库流水 → 组装上下文
 *   → 阶段分类(StageClassifier) → 状态机推进(DealService/StageMachine)
 *   → 画像沉淀(CustomerProfileService)
 *   → 策略选择(StrategyService) → 模板渲染 → LLM润色(LLMClient)
 *   → 合规校验(ComplianceFilter) → 人工闸门(ReplyDraft PENDING)
 *   → [人工审批] → 外发(OutboundSender) → 审计(ActionLogger)
 * </pre>
 *
 * <p>回调侧只做「快速落库 + 投递总线」；本编排器在消息总线监听器中执行，
 * 与企微回调解耦（对应 M1 中"回调 5 秒 ack + 异步处理"设计）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SalesAgentOrchestrator implements MessageListener {

    private final MessageBus messageBus;
    private final MessageLogRepository messageLogRepository;
    private final ReplyDraftRepository draftRepository;
    private final ContextAssemblyService contextAssemblyService;
    private final CustomerProfileService profileService;
    private final StageClassifier stageClassifier;
    private final DealService dealService;
    private final StrategyService strategyService;
    private final LLMClient llmClient;
    private final ComplianceFilter complianceFilter;
    private final QuoteService quoteService;
    private final DraftApprovalService draftApprovalService;
    private final ActionLogger actionLogger;
    private final AppProperties appProperties;

    @PostConstruct
    public void subscribe() {
        messageBus.register(this);
        log.info("[SalesAgentOrchestrator] 已订阅 MessageBus，开始监听客户消息");
    }

    @Override
    @Transactional
    public void onMessage(Message message) {
        if (message == null || message.getContactExternalId() == null || message.getContactExternalId().isBlank()) {
            log.warn("[Orchestrator] 忽略无客户标识的消息: {}", message);
            return;
        }
        try {
            handleInbound(message);
        } catch (Exception e) {
            // 单条消息处理失败不允许中断总线（尽量留存上下文，可在日志/管理端查看）
            log.error("[Orchestrator] 处理客户消息异常 msgId={} err={}", message.getMsgId(), e.getMessage(), e);
            actionLogger.log(null, "ORCHESTRATE_FAILED",
                    "msgId=" + message.getMsgId() + " error=" + e.getMessage());
        }
    }

    private void handleInbound(Message message) {
        // 1) msgId 幂等去重（企微同一消息可能因回调重投多次）
        if (messageLogRepository.existsByMsgId(message.getMsgId())) {
            log.info("[Orchestrator] 消息已处理过，跳过 msgId={}", message.getMsgId());
            return;
        }

        // 2) 客户建档（首次出现自动建 contact）
        Contact contact = contextAssemblyService.requireContactByExternalId(message.getContactExternalId());

        // 2.5) OUT（员工在企微侧的发言，经群聊/单聊存档映射而来）：仅落库补充对话上下文，
        //      不触发分类/策略/草稿，避免把员工消息当客户消息回复
        if (message.getDirection() == Direction.OUT) {
            MessageLog staffMsg = new MessageLog();
            staffMsg.setMsgId(message.getMsgId());
            staffMsg.setContactId(contact.getId());
            staffMsg.setDirection(Direction.OUT.name());
            staffMsg.setSenderType(message.getSenderType() == null ? "STAFF" : message.getSenderType());
            staffMsg.setContent(message.getContent());
            staffMsg.setMsgType("text");
            staffMsg.setChannelType(message.getChannelType());
            staffMsg.setProcessed(Boolean.TRUE);
            staffMsg.touch();
            messageLogRepository.save(staffMsg);
            actionLogger.log(contact.getId(), "STAFF_MESSAGE_ARCHIVED",
                    "msgId=" + message.getMsgId() + " 内容=" + truncate(message.getContent()));
            return;
        }

        // 3) 客户消息落库（先落库保证审计完整）
        MessageLog inbound = new MessageLog();
        inbound.setMsgId(message.getMsgId());
        inbound.setContactId(contact.getId());
        inbound.setDirection(Direction.IN.name());
        inbound.setSenderType("CUSTOMER");
        inbound.setContent(message.getContent());
        inbound.setMsgType("text");
        inbound.setChannelType(message.getChannelType());
        inbound.setProcessed(Boolean.TRUE);
        inbound.touch();
        messageLogRepository.save(inbound);

        // 4) 组装上下文（含刚落库的最近 N 轮）
        ContactContext ctx = contextAssemblyService.assemble(contact);

        // 5) 阶段分类 + 状态机推进
        SalesStage suggested = stageClassifier.classify(ctx);
        DealService.StageUpdateResult update = dealService.applyClassifiedStage(contact, suggested);
        Deal deal = update.deal();

        // 6) 画像沉淀（阶段跃迁时写长期摘要）
        profileService.updateAfterMessage(contact, message.getContent(), deal.getStage(), update.changed());

        // 7) 重新组装（拿到最新阶段与画像）
        ContactContext freshCtx = contextAssemblyService.assemble(contact);

        // 8) 策略选择（按阶段 + 触发条件）
        StrategyConfig rule = strategyService.selectForMessage(freshCtx, message.getContent());
        if (rule == null) {
            log.info("[Orchestrator] 客户 {} 当前阶段 {} 无可用回复策略，等待人工介入",
                    contact.getName(), deal.getStage());
            return;
        }

        // 8.5) HUMAN_TRANSFER 转人工：客户主动要求/投诉等场景交回人工，不生成 AI 话术、
        //      不受防骚扰间隔限制，仅写审计标记与日志即结束（由人工在管理端介入）
        if (StrategyService.ACTION_HUMAN_TRANSFER.equals(rule.getActionType())) {
            actionLogger.log(contact.getId(), "HUMAN_TRANSFER_REQUESTED",
                    "strategy=" + rule.getRuleName() + " stage=" + deal.getStage().name()
                            + " 触发消息=" + truncate(message.getContent()));
            log.info("[Orchestrator] 客户 {} 命中转人工策略[{}]，已记录审计标记，等待人工介入",
                    contact.getName(), rule.getRuleName());
            return;
        }

        // 8.6) 策略级最小发送间隔二次校验（DESIGN.md §7.4）：距最近一次外发不足间隔则静默跳过，
        //      不生成草稿、不调报价，避免营销话术短时间轰炸同一客户
        if (violatesMinInterval(contact.getId(), rule)) {
            actionLogger.log(contact.getId(), "STRATEGY_INTERVAL_SKIPPED",
                    "strategy=" + rule.getRuleName() + " minIntervalMinutes=" + rule.getMinIntervalMinutes()
                            + " 触发消息=" + truncate(message.getContent()));
            return;
        }

        // 9) 动作类型：CREATE_QUOTE 先调报价 SPI（对接业务系统）
        String quoteReference = null;
        if (StrategyService.ACTION_CREATE_QUOTE.equals(rule.getActionType())) {
            QuoteRequest quoteReq = new QuoteRequest();
            quoteReq.setContactId(contact.getId());
            QuoteResult quoteResult = quoteService.createQuote(quoteReq);
            quoteReference = quoteResult.quoteNo();
            actionLogger.log(contact.getId(), "QUOTE_CREATED",
                    "quoteNo=" + quoteReference + " amount=" + quoteResult.amount() + " via=" + quoteService.getClass().getSimpleName());
        }

        // 10) 模板渲染 + LLM 润色
        String rendered = strategyService.render(rule, freshCtx, quoteReference);
        String content = llmClient.generate(freshCtx, rule, rendered);

        // 11) 合规校验（敏感词/长度）
        ComplianceResult compliance = complianceFilter.check(content);

        // 12) 生成待审批草稿
        ReplyDraft draft = new ReplyDraft();
        draft.setContactId(contact.getId());
        draft.setSourceMsgId(message.getMsgId());
        draft.setStage(deal.getStage().name());
        draft.setStrategyName(rule.getRuleName());
        draft.setActionType(rule.getActionType());
        draft.setContent(content);
        draft.setReason("命中策略[" + rule.getRuleName() + "] 阶段=" + deal.getStage().name()
                + " 分类器=" + stageClassifier.implName() + " LLM=" + llmClient.implName());
        draft.setQuoteReference(quoteReference);
        draft.touch();
        if (!compliance.passed()) {
            draft.block(compliance.reason());
        }
        draftRepository.save(draft);
        actionLogger.log(contact.getId(), "DRAFT_CREATED",
                "draftId=" + draft.getId() + " status=" + draft.getStatus()
                        + " stage=" + deal.getStage().name() + " strategy=" + rule.getRuleName());

        // 13) 人工闸门：MANUAL 停在 PENDING；AUTO 直接批准发送（仅测试用）
        boolean manual = !"AUTO".equalsIgnoreCase(appProperties.getApprovalMode());
        if (!manual && ReplyDraft.STATUS_PENDING.equals(draft.getStatus())) {
            draftApprovalService.autoApproveAndSend(draft);
            actionLogger.log(contact.getId(), "AUTO_APPROVED",
                    "draftId=" + draft.getId() + " AUTO 模式自动发送");
        } else if (ReplyDraft.STATUS_BLOCKED.equals(draft.getStatus())) {
            log.warn("[Orchestrator] 草稿 draftId={} 被合规校验阻断: {}", draft.getId(), compliance.reason());
        } else {
            log.info("[Orchestrator] 草稿 draftId={} 进入人工审批队列（PENDING）", draft.getId());
        }
    }

    /**
     * 策略级最小发送间隔校验：minIntervalMinutes>0 且该客户最近一次外发
     * （任意通道/人工或 Agent）距今不足间隔时返回 true。
     */
    private boolean violatesMinInterval(Long contactId, StrategyConfig rule) {
        Integer minutes = rule.getMinIntervalMinutes();
        if (minutes == null || minutes <= 0) {
            return false;
        }
        return messageLogRepository
                .findTop1ByContactIdAndDirectionOrderByCreatedAtDesc(contactId, Direction.OUT.name())
                .map(MessageLog::getCreatedAt)
                .map(last -> last.plusMinutes(minutes).isAfter(LocalDateTime.now()))
                .orElse(false);
    }

    private String truncate(String s) {
        return s == null ? "" : (s.length() <= 100 ? s : s.substring(0, 100) + "...");
    }
}

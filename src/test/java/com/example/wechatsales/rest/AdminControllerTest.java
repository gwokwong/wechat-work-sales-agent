package com.example.wechatsales.rest;

import com.example.wechatsales.action.ActionLogger;
import com.example.wechatsales.channel.MockChannel;
import com.example.wechatsales.channel.OutboundSender;
import com.example.wechatsales.channel.SendResult;
import com.example.wechatsales.context.ContextAssemblyService;
import com.example.wechatsales.domain.Contact;
import com.example.wechatsales.repository.ContactRepository;
import com.example.wechatsales.repository.CustomerProfileRepository;
import com.example.wechatsales.repository.DealRepository;
import com.example.wechatsales.repository.MessageLogRepository;
import com.example.wechatsales.repository.QuoteRequestRepository;
import com.example.wechatsales.repository.ReplyDraftRepository;
import com.example.wechatsales.stage.DealService;
import com.example.wechatsales.stage.StageMachine;
import com.example.wechatsales.strategy.DraftApprovalService;
import com.example.wechatsales.strategy.StrategyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 管理端 REST 接口单测：客户列表、手动外发成功/失败分支。
 */
@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    @Mock
    private ContactRepository contactRepository;
    @Mock
    private CustomerProfileRepository profileRepository;
    @Mock
    private MessageLogRepository messageLogRepository;
    @Mock
    private DealRepository dealRepository;
    @Mock
    private QuoteRequestRepository quoteRequestRepository;
    @Mock
    private ReplyDraftRepository draftRepository;
    @Mock
    private ContextAssemblyService contextAssemblyService;
    @Mock
    private DealService dealService;
    @Mock
    private StageMachine stageMachine;
    @Mock
    private DraftApprovalService draftApprovalService;
    @Mock
    private OutboundSender outboundSender;
    @Mock
    private ActionLogger actionLogger;
    @Mock
    private StrategyService strategyService;
    @Mock
    private MockChannel mockChannel;

    private AdminController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminController(contactRepository, profileRepository, messageLogRepository,
                dealRepository, quoteRequestRepository, draftRepository, contextAssemblyService,
                dealService, stageMachine, draftApprovalService, outboundSender, actionLogger,
                strategyService, mockChannel);
    }

    @Test
    void customersAndManualSendSuccessFailureBranches() {
        Contact contact = new Contact();
        contact.setId(1L);
        contact.setName("王总");
        when(contactRepository.findAll()).thenReturn(List.of(contact));
        ApiResponse<List<Contact>> customers = controller.customers();
        assertEquals(0, customers.code());
        assertEquals(1, customers.data().size());

        // 发送失败 → 500 + 错误说明；随后重试成功 → 200 + messageId
        when(outboundSender.sendByContactId(eq(1L), anyString()))
                .thenReturn(SendResult.fail("mock 通道未配置"), SendResult.ok("mock-msg-9"));

        ApiResponse<SendResult> failed = controller.manualSend(1L, new AdminController.SendRequest("你好"));
        assertEquals(500, failed.code());
        assertTrue(failed.message().contains("mock 通道未配置"));

        ApiResponse<SendResult> ok = controller.manualSend(1L, new AdminController.SendRequest("你好"));
        assertEquals(0, ok.code());
        assertTrue(ok.data().success());
        assertEquals("mock-msg-9", ok.data().messageId());
    }
}

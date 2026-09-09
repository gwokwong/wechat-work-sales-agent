package com.example.wechatsales.strategy;

import com.example.wechatsales.config.AppProperties;
import com.example.wechatsales.context.ContactContext;
import com.example.wechatsales.domain.Contact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * HttpLLMClient 单测：用 MockRestServiceServer 拦截 RestClient 真实 HTTP 层，
 * 覆盖成功解析 choices[0].message.content / HTTP 500 回退原话术 / 空或非法响应回退原话术。
 */
@ExtendWith(MockitoExtension.class)
class HttpLLMClientTest {

    private static final String BASE_URL = "https://llm.test/v1";

    private AppProperties appProperties;
    private RestClient.Builder restClientBuilder;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.getLlm().setMock(false);
        appProperties.getLlm().setBaseUrl(BASE_URL);
        appProperties.getLlm().setApiKey("sk-test-123");
        appProperties.getLlm().setModel("deepseek-chat");
        appProperties.getLlm().setTemperature(0.5);
        restClientBuilder = RestClient.builder();
    }

    @Test
    void successReturnsPolishedContentFromChoices() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        HttpLLMClient client = new HttpLLMClient(restClientBuilder, appProperties);
        String polished = "您好，感谢您的关注！我们可以为您详细说明方案与实施安排，方便时继续沟通。";

        server.expect(requestTo(BASE_URL + HttpLLMClient.CHAT_COMPLETIONS_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sk-test-123"))
                .andExpect(jsonPath("$.model").value("deepseek-chat"))
                .andExpect(jsonPath("$.temperature").value(0.5))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content", containsString("润色")))
                .andExpect(jsonPath("$.messages[0].content", containsString("不得新增任何承诺、价格或折扣")))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content", containsString("【待润色话术】")))
                .andExpect(jsonPath("$.messages[1].content", containsString("客户=张总")))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + polished + "\"}}]}",
                        MediaType.APPLICATION_JSON));

        String draft = "感谢关注，我们可以介绍方案。";
        assertEquals(polished, client.generate(context(), strategy(), draft));
    }

    @Test
    void http500FallsBackToOriginalDraft() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        HttpLLMClient client = new HttpLLMClient(restClientBuilder, appProperties);
        String draft = "原话术不应丢失";

        server.expect(requestTo(BASE_URL + HttpLLMClient.CHAT_COMPLETIONS_PATH))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("llm boom").contentType(MediaType.APPLICATION_JSON));

        assertEquals(draft, client.generate(context(), strategy(), draft));
    }

    @Test
    void emptyChoicesFallsBackToOriginalDraft() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        HttpLLMClient client = new HttpLLMClient(restClientBuilder, appProperties);
        String draft = "空或异常响应也应回退原话术";

        server.expect(requestTo(BASE_URL + HttpLLMClient.CHAT_COMPLETIONS_PATH))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));
        assertEquals(draft, client.generate(context(), strategy(), draft));
    }

    @Test
    void nonJsonResponseFallsBackToOriginalDraft() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        HttpLLMClient client = new HttpLLMClient(restClientBuilder, appProperties);
        String draft = "空或异常响应也应回退原话术";

        server.expect(requestTo(BASE_URL + HttpLLMClient.CHAT_COMPLETIONS_PATH))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("<html>bad gateway</html>", MediaType.APPLICATION_JSON));
        assertEquals(draft, client.generate(context(), strategy(), draft));
    }

    private ContactContext context() {
        Contact contact = new Contact();
        contact.setName("张总");
        return ContactContext.builder()
                .contact(contact)
                .recentCustomerMessages(List.of("我们想了解下方案报价", "预算大概十万以内"))
                .build();
    }

    private StrategyConfig strategy() {
        StrategyConfig s = new StrategyConfig();
        s.setRuleName("PROPOSAL_PRICING");
        s.setStage("PROPOSAL");
        return s;
    }
}

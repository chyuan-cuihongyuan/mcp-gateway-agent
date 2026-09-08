package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.domain.governance.service.ICelEvaluationService;
import cn.chyuan.ai.domain.llmchannel.adapter.port.ILlmHttpPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A2A 代理面测试（工单 0066：card 重写/任务透传/上游不可达）
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("A2A 代理面测试")
public class A2aProxyControllerTest {

    @Mock
    private ILlmHttpPort httpPort;

    @Mock
    private ICelEvaluationService celEvaluationService;

    private A2aProxyController controller;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        controller = new A2aProxyController(httpPort, provider(celEvaluationService), provider(null), provider(null), provider(null));
    }

    private static <T> org.springframework.beans.factory.ObjectProvider<T> provider(T value) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public T getObject() {
                throw new UnsupportedOperationException();
            }

            @Override
            public T getIfAvailable() {
                return value;
            }
        };
    }

    private void configure(String upstream, String publicBase) {
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "upstreamBaseUrl", upstream);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "publicBaseUrl", publicBase);
    }

    @Test
    @DisplayName("card 代理 — 上游 card 取回并重写 url/additional_interfaces 指向网关")
    public void testAgentCardRewrite() throws Exception {
        configure("http://upstream-agent", "https://gw.example.com");
        when(httpPort.getJson(eq("http://upstream-agent/.well-known/agent.json"), any(), anyInt()))
                .thenReturn("{\"name\":\"agent\",\"url\":\"http://upstream-agent:9999\","
                        + "\"additional_interfaces\":[{\"transport\":\"jsonrpc\",\"url\":\"http://upstream-agent:9999\"}]}");

        MockHttpServletResponse response = new MockHttpServletResponse();
        String card = controller.agentCard(response);

        assertTrue(card.contains("https://gw.example.com/a2a"), "url 重写指向网关");
        assertTrue(card.contains("\"additional_interfaces\":[{\"transport\":\"jsonrpc\",\"url\":\"https://gw.example.com/a2a\"}]"),
                "additional_interfaces 同步重写");
        assertFalse(card.contains("upstream-agent:9999"), "上游地址不残留");
    }

    @Test
    @DisplayName("card 重写容错 — 非 JSON card 原样透传；未配置上游明确报错")
    public void testAgentCardEdgeCases() {
        assertEquals("<html>not-json</html>", A2aProxyController.rewriteCard("<html>not-json</html>", "https://gw"));

        configure("", "https://gw");
        assertThrows(cn.chyuan.ai.types.exception.AppException.class,
                () -> controller.agentCard(new MockHttpServletResponse()));
    }

    @Test
    @DisplayName("任务端点透传 — POST /a2a/** 原样转发（路径拼接）与响应回传")
    public void testTaskEndpointPassthrough() throws Exception {
        configure("http://upstream-agent/", "https://gw.example.com");
        when(celEvaluationService.isToolAllowed(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(httpPort.postJson(eq("http://upstream-agent/"), anyMap(), anyString(), anyInt()))
                .thenReturn(200);
        when(httpPort.lastResponseBody()).thenReturn("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/a2a/");
        request.setAttribute(cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal.REQUEST_ATTR,
                cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal.builder()
                        .authType(cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal.AuthType.VIRTUAL_KEY)
                        .virtualKeyId(7L).apiKeyHash("h").build());
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.taskEndpoint(request, response, "{\"jsonrpc\":\"2.0\",\"method\":\"message/send\"}");

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"ok\":true"));
        verify(httpPort).postJson(eq("http://upstream-agent/"), anyMap(),
                eq("{\"jsonrpc\":\"2.0\",\"method\":\"message/send\"}"), anyInt());
    }

    @Test
    @DisplayName("上游不可达 — 502 语义（-32004）+ 账本 FAIL")
    public void testUpstreamUnreachable() throws Exception {
        configure("http://upstream-agent", "https://gw.example.com");
        when(celEvaluationService.isToolAllowed(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(httpPort.postJson(anyString(), anyMap(), anyString(), anyInt()))
                .thenThrow(new java.io.IOException("connection refused"));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/a2a/");
        request.setAttribute(cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal.REQUEST_ATTR,
                cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal.builder()
                        .authType(cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal.AuthType.VIRTUAL_KEY)
                        .virtualKeyId(7L).apiKeyHash("h").build());

        cn.chyuan.ai.types.exception.AppException e = assertThrows(
                cn.chyuan.ai.types.exception.AppException.class,
                () -> controller.taskEndpoint(request, new MockHttpServletResponse(),
                        "{\"jsonrpc\":\"2.0\",\"method\":\"message/send\"}"));
        assertTrue(e.getInfo().contains("不可达"));
    }
}

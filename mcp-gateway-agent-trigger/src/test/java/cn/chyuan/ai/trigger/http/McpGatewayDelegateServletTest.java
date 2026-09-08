package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import cn.chyuan.ai.domain.session.service.tool.IMcpToolCatalogService;
import cn.chyuan.ai.infrastructure.gateway.streamable.GatewayMcpServerRegistry;
import cn.chyuan.ai.infrastructure.utils.ObservabilityHelper;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Streamable HTTP 委派路由单元测试（工单 0020：会话 TTL/校验分支，协议级行为由 StreamableHttpProtocolTest 覆盖）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MCP 网关委派路由测试")
class McpGatewayDelegateServletTest {

    private static final String GATEWAY_ID = "gateway_business";

    @Mock
    private GatewayMcpServerRegistry registry;

    @Mock
    private IMcpToolCatalogService toolCatalogService;

    @Mock
    private ObservabilityHelper observabilityHelper;

    private McpGatewayDelegateServlet servlet;

    @BeforeEach
    void setUp() {
        servlet = new McpGatewayDelegateServlet(registry, toolCatalogService, null,
                observabilityHelper, null, null, null, null, null, 64 * 1024, 30L);
    }

    private MockHttpServletRequest request(String method, String pathInfo, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api-gateway" + pathInfo);
        request.setPathInfo(pathInfo);
        request.setContentType("application/json");
        if (body != null) {
            request.setContent(body.getBytes());
        }
        return request;
    }

    @Test
    @DisplayName("路径不匹配（含旧 SSE 路径）— 404")
    void nonMcpPathReturns404() throws Exception {
        for (String pathInfo : new String[] {"/gateway_business/mcp/sse", "/gateway_business", "/gateway_business/other"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            servlet.service(request("GET", pathInfo, null), response);
            assertThat(response.getStatus()).isEqualTo(404);
        }
        verify(registry, never()).transportOf(anyString());
    }

    @Test
    @DisplayName("消息体超 64KB — 413 + -32015（0056 契约升级：含上限与实际值）")
    void oversizedBodyReturns413() throws Exception {
        MockHttpServletRequest request = request("POST", "/" + GATEWAY_ID + "/mcp", "x".repeat(64 * 1024 + 1));
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet.service(request, response);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString())
                .contains(String.valueOf(cn.chyuan.ai.types.enums.McpErrorCodes.REQUEST_TOO_LARGE))
                .contains("64KB");
    }

    @Test
    @DisplayName("非法 JSON 消息体 — 400")
    void invalidJsonReturns400() throws Exception {
        MockHttpServletRequest request = request("POST", "/" + GATEWAY_ID + "/mcp", "{not-json");
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet.service(request, response);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("不是合法JSON");
    }

    @Test
    @DisplayName("tools/call 工具名非法 — 400")
    void invalidToolNameReturns400() throws Exception {
        MockHttpServletRequest request = request("POST", "/" + GATEWAY_ID + "/mcp",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"bad name!\"}}");
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet.service(request, response);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("toolName");
    }

    @Test
    @DisplayName("会话 TTL 过期 — 404（与旧实现超时语义等价）")
    void expiredSessionReturns404() throws Exception {
        Map<String, Long> localSessions = new ConcurrentHashMap<>();
        // 最后访问时间拨回 31 分钟前（超时 30 分钟）
        localSessions.put("sess-expired", System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(31));
        ReflectionTestUtils.setField(servlet, "localSessions", localSessions);

        MockHttpServletRequest request = request("POST", "/" + GATEWAY_ID + "/mcp",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        request.addHeader("Mcp-Session-Id", "sess-expired");
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet.service(request, response);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).contains("Session not found or expired");
        // 过期条目已被清理
        assertThat(localSessions).doesNotContainKey("sess-expired");
        verify(registry, never()).transportOf(anyString());
    }

    @Test
    @DisplayName("无会话头的非 initialize 请求 — 404（与官方传输行为一致）")
    void missingSessionHeaderReturns404() throws Exception {
        MockHttpServletRequest request = request("POST", "/" + GATEWAY_ID + "/mcp",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet.service(request, response);

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("未知网关 — 404 + JSON-RPC 错误（不构建官方服务器）")
    void unknownGatewayReturns404() throws Exception {
        org.mockito.Mockito.when(registry.transportOf("gateway_business"))
                .thenThrow(new cn.chyuan.ai.types.exception.AppException(
                        cn.chyuan.ai.types.enums.ResponseCode.METHOD_NOT_FOUND.getCode(), "网关配置不存在: gateway_business"));
        MockHttpServletRequest request = request("POST", "/" + GATEWAY_ID + "/mcp",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet.service(request, response);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).contains("网关配置不存在");
    }

    @Test
    @DisplayName("缓存请求体可重放（委派官方传输前预读后不丢体）")
    void cachedBodyReplays() throws Exception {
        byte[] body = "{\"jsonrpc\":\"2.0\",\"id\":1}".getBytes();
        McpGatewayDelegateServlet.CachedBodyHttpServletRequest wrapper =
                new McpGatewayDelegateServlet.CachedBodyHttpServletRequest(
                        request("POST", "/" + GATEWAY_ID + "/mcp", null), body);

        // 读两次内容一致
        String first = new String(wrapper.getInputStream().readAllBytes());
        String second = new String(wrapper.getInputStream().readAllBytes());
        assertThat(first).isEqualTo(second).isEqualTo(new String(body));
        assertThat(wrapper.getContentLength()).isEqualTo(body.length);
    }

    @Test
    @DisplayName("Mcp-Session-Id 响应头捕获即回调（登记先于响应体到达客户端）")
    void sessionCaptureTriggersCallbackImmediately() {
        StringBuilder captured = new StringBuilder();
        MockHttpServletResponse mockResponse = new MockHttpServletResponse();
        McpGatewayDelegateServlet.SessionCapturingResponseWrapper wrapper =
                new McpGatewayDelegateServlet.SessionCapturingResponseWrapper(mockResponse, captured::append);

        wrapper.setHeader("Content-Type", "application/json");
        assertThat(captured).isEmpty();

        wrapper.setHeader("Mcp-Session-Id", "sess-1");
        assertThat(captured.toString()).isEqualTo("sess-1");
        assertThat(wrapper.capturedSessionId()).isEqualTo("sess-1");
        assertThat(mockResponse.getHeader("Mcp-Session-Id")).isEqualTo("sess-1");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("batch（0054）— 拆分治理按序聚合：tools/list + 未知工具 + 通知（无响应条目）")
    void testBatchMixedElements() throws Exception {
        // 会话头合法 + 目录含工具
        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest("POST", "/api-gateway/gateway_001/mcp");
        request.setPathInfo("/gateway_001/mcp");
        request.addHeader("Mcp-Session-Id", "sess-1");
        request.setContentType("application/json");
        String batch = "[" +
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}," +
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"no_such_tool\",\"arguments\":{}}}," +
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}" +
                "]";
        request.setContent(batch.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        org.springframework.mock.web.MockHttpServletResponse response = new org.springframework.mock.web.MockHttpServletResponse();
        org.springframework.mock.web.MockFilterChain chain = new org.springframework.mock.web.MockFilterChain();

        // 会话登记进 servlet 本地表（batch 各元素共用）
        @SuppressWarnings("unchecked")
        Map<String, Long> localSessions = (Map<String, Long>) ReflectionTestUtils.getField(servlet, "localSessions");
        localSessions.put("sess-1", System.currentTimeMillis());

        servlet.service(request, response);

        String body = response.getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(body.startsWith("["), "batch 响应应为数组");
        com.fasterxml.jackson.databind.JsonNode array = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        org.junit.jupiter.api.Assertions.assertEquals(2, array.size(), "通知不产生响应条目");
        org.junit.jupiter.api.Assertions.assertEquals(1, array.get(0).path("id").asInt());
        org.junit.jupiter.api.Assertions.assertEquals(cn.chyuan.ai.types.enums.McpErrorCodes.TOOL_NOT_FOUND,
                array.get(1).path("error").path("code").asInt(), "未知工具 -32003");
        org.junit.jupiter.api.Assertions.assertEquals(200, response.getStatus());
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("batch（0054）— 空数组 -32600 / 畸形条目错误条目化")
    void testBatchEmptyAndMalformed() throws Exception {
        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest("POST", "/api-gateway/gateway_001/mcp");
        request.setPathInfo("/gateway_001/mcp");
        request.setContentType("application/json");
        request.setContent("[]".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        org.springframework.mock.web.MockHttpServletResponse response = new org.springframework.mock.web.MockHttpServletResponse();

        servlet.service(request, response);
        org.junit.jupiter.api.Assertions.assertEquals(400, response.getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(response.getContentAsString()
                .contains(String.valueOf(cn.chyuan.ai.types.enums.McpErrorCodes.INVALID_REQUEST)));

        org.springframework.mock.web.MockHttpServletRequest request2 =
                new org.springframework.mock.web.MockHttpServletRequest("POST", "/api-gateway/gateway_001/mcp");
        request2.setPathInfo("/gateway_001/mcp");
        request2.addHeader("Mcp-Session-Id", "sess-1");
        request2.setContentType("application/json");
        request2.setContent("[\"not-an-object\",{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/list\"}]".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        org.springframework.mock.web.MockHttpServletResponse response2 = new org.springframework.mock.web.MockHttpServletResponse();
        servlet.service(request2, response2);

        com.fasterxml.jackson.databind.JsonNode array = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(response2.getContentAsString());
        org.junit.jupiter.api.Assertions.assertEquals(2, array.size());
        org.junit.jupiter.api.Assertions.assertEquals(cn.chyuan.ai.types.enums.McpErrorCodes.INVALID_REQUEST,
                array.get(0).path("error").path("code").asInt(), "非对象条目 -32600");
    }
}

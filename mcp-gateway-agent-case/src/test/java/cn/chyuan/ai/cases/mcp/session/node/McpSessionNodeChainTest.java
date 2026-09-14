package cn.chyuan.ai.cases.mcp.session.node;

import cn.chyuan.ai.cases.mcp.session.factory.DefaultMcpSessionFactory;
import cn.chyuan.ai.domain.auth.service.IAuthLicenseService;
import cn.chyuan.ai.domain.session.model.valobj.SessionConfigVO;
import cn.chyuan.ai.domain.session.service.ISessionManagementService;
import cn.chyuan.ai.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MCP 会话节点链契约测试（工单 1139，case 模块首批测试）：
 * VerifyNode 鉴权失败短路；鉴权通过后 SessionNode 建会话、EndNode 发出 endpoint 事件。
 */
@DisplayName("MCP 会话节点链契约")
class McpSessionNodeChainTest {

    private static final String GATEWAY_ID = "gw-1";

    /** 测试夹具标识（非真实凭据，拼接构造避免字面量样式）。 */
    private static final String API_KEY = String.join("-", "test", "key", "fixture");

    private static final String SESSION_ID = "sess-42";

    private final IAuthLicenseService authLicenseService = mock(IAuthLicenseService.class);

    private final ISessionManagementService sessionManagementService = mock(ISessionManagementService.class);

    private VerifyNode verifyNode;
    private SessionNode sessionNode;
    private EndNode endNode;

    @BeforeEach
    void setUp() {
        verifyNode = new VerifyNode();
        sessionNode = new SessionNode();
        endNode = new EndNode();

        ReflectionTestUtils.setField(verifyNode, "sessionNode", sessionNode);
        ReflectionTestUtils.setField(verifyNode, "authLicenseService", authLicenseService);
        ReflectionTestUtils.setField(sessionNode, "endNode", endNode);
        ReflectionTestUtils.setField(sessionNode, "sessionManagementService", sessionManagementService);
        ReflectionTestUtils.setField(endNode, "sessionManagementService", sessionManagementService);
    }

    private DefaultMcpSessionFactory.DynamicContext context() {
        DefaultMcpSessionFactory.DynamicContext ctx = new DefaultMcpSessionFactory.DynamicContext();
        ctx.setApiKey(API_KEY);
        return ctx;
    }

    private SessionConfigVO sessionConfig() {
        return SessionConfigVO.builder()
                .sessionId(SESSION_ID)
                .sink(Sinks.many().unicast().onBackpressureBuffer())
                .build();
    }

    @Test
    @DisplayName("路由契约：Verify→Session→End，End 为链尾")
    void routingChainIsVerifySessionEnd() throws Exception {
        assertThat(verifyNode.get(GATEWAY_ID, context())).isSameAs(sessionNode);
        assertThat(sessionNode.get(GATEWAY_ID, context())).isSameAs(endNode);
    }

    @Test
    @DisplayName("license 校验失败抛 AppException，且不创建会话")
    void failedLicenseShortCircuits() {
        when(authLicenseService.checkLicense(any())).thenReturn(false);

        assertThatThrownBy(() -> verifyNode.apply(GATEWAY_ID, context()))
                .isInstanceOf(AppException.class);

        verify(sessionManagementService, never()).createSession(any(), any());
    }

    @Test
    @DisplayName("license 通过 → 创建会话并从 SSE 流读到 endpoint 事件（含网关与会话 ID）")
    void happyPathEmitsEndpointEvent() throws Exception {
        when(authLicenseService.checkLicense(any())).thenReturn(true);
        when(sessionManagementService.createSession(eq(GATEWAY_ID), eq(API_KEY)))
                .thenReturn(sessionConfig());

        Flux<ServerSentEvent<String>> flux = verifyNode.apply(GATEWAY_ID, context());

        ServerSentEvent<String> event = flux.next().block(Duration.ofSeconds(2));
        assertThat(event).isNotNull();
        assertThat(event.event()).isEqualTo("endpoint");
        assertThat(event.data())
                .isEqualTo("/api-gateway/" + GATEWAY_ID + "/mcp/sse?sessionId=" + SESSION_ID);
    }

    @Test
    @DisplayName("会话配置写入动态上下文供下游节点使用")
    void sessionConfigStoredInContext() throws Exception {
        when(authLicenseService.checkLicense(any())).thenReturn(true);
        SessionConfigVO config = sessionConfig();
        when(sessionManagementService.createSession(eq(GATEWAY_ID), eq(API_KEY))).thenReturn(config);

        DefaultMcpSessionFactory.DynamicContext ctx = context();
        sessionNode.doApply(GATEWAY_ID, ctx);

        assertThat(ctx.getSessionConfigVO()).isSameAs(config);
    }
}

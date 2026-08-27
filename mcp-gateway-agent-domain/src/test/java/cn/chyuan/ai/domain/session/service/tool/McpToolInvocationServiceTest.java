package cn.chyuan.ai.domain.session.service.tool;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import cn.chyuan.ai.domain.governance.service.ICelEvaluationService;
import cn.chyuan.ai.domain.session.adapter.port.ISessionPort;
import cn.chyuan.ai.domain.session.adapter.repository.ISessionRepository;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolProtocolConfigVO;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * tools/call 调用服务测试（工单 0020：承接 0018 的 tools/call 生效点语义）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MCP 工具调用服务测试")
class McpToolInvocationServiceTest {

    @Mock
    private ISessionRepository repository;

    @Mock
    private ISessionPort port;

    @Mock
    private ICelEvaluationService celEvaluationService;

    private McpToolInvocationService service;

    private final GovernancePrincipal principal = GovernancePrincipal.builder()
            .authType(GovernancePrincipal.AuthType.VIRTUAL_KEY)
            .virtualKeyId(42L)
            .build();

    @BeforeEach
    void setUp() {
        service = new McpToolInvocationService();
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "port", port);
        ReflectionTestUtils.setField(service, "celEvaluationService", celEvaluationService);
        lenient().when(celEvaluationService.isToolAllowed(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
    }

    private McpToolProtocolConfigVO protocolConfig() {
        McpToolProtocolConfigVO.HTTPConfig httpConfig = new McpToolProtocolConfigVO.HTTPConfig();
        httpConfig.setHttpUrl("http://127.0.0.1:18099/api/tools/query");
        httpConfig.setHttpMethod("POST");
        return McpToolProtocolConfigVO.builder()
                .httpConfig(httpConfig)
                .build();
    }

    @Test
    @DisplayName("工具不存在 — AppException 携带 -32003（TOOL_NOT_FOUND），不触达下游")
    void unknownToolThrowsToolNotFound() throws Exception {
        when(repository.queryMcpGatewayProtocolConfig("gw-1", "ghost_tool")).thenReturn(null);

        assertThatThrownBy(() -> service.invoke("gw-1", "ghost_tool", Map.of("orderId", "o-1"), principal))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getCode())
                        .isEqualTo(String.valueOf(McpErrorCodes.TOOL_NOT_FOUND)));
        verify(port, never()).toolCall(any(), any());
    }

    @Test
    @DisplayName("CEL 拒绝 — AppException 携带 -32006（无权限），与不存在区分")
    void celDeniedThrowsInsufficientPermissions() throws Exception {
        when(repository.queryMcpGatewayProtocolConfig("gw-1", "write_tool")).thenReturn(protocolConfig());
        when(celEvaluationService.isToolAllowed(eq(principal), eq("gw-1"), eq("tools/call"), eq("write_tool"),
                eq("PROTOCOL"))).thenReturn(false);

        assertThatThrownBy(() -> service.invoke("gw-1", "write_tool", Map.of("orderId", "o-1"), principal))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getCode())
                        .isEqualTo(String.valueOf(McpErrorCodes.INSUFFICIENT_PERMISSIONS)));
        verify(port, never()).toolCall(any(), any());
    }

    @Test
    @DisplayName("CEL 放行 — 正常执行并透传下游载荷")
    void celAllowedExecutesToolCall() throws Exception {
        when(repository.queryMcpGatewayProtocolConfig("gw-1", "query_order")).thenReturn(protocolConfig());
        when(port.toolCall(any(), any())).thenReturn("{\"result\":\"ok\"}");

        Object payload = service.invoke("gw-1", "query_order", Map.of("orderId", "o-1"), principal);

        assertThat(payload).isEqualTo("{\"result\":\"ok\"}");
        verify(port).toolCall(any(), any());
    }

    @Test
    @DisplayName("参数为空 — 非法参数异常")
    void nullArgumentsThrowsIllegalParameter() {
        assertThatThrownBy(() -> service.invoke("gw-1", "query_order", null, principal))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getCode())
                        .isEqualTo(ResponseCode.ILLEGAL_PARAMETER.getCode()));
    }
}

package cn.chyuan.ai.domain.session.service.message.handler.impl;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import cn.chyuan.ai.domain.governance.service.ICelEvaluationService;
import cn.chyuan.ai.domain.session.adapter.port.ISessionPort;
import cn.chyuan.ai.domain.session.adapter.repository.ISessionRepository;
import cn.chyuan.ai.domain.session.model.valobj.McpSchemaVO;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolProtocolConfigVO;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * tools/call 治理测试（工单 0018 验收：无权限与工具不存在以错误码区分）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("tools/call CEL 治理测试")
class ToolsCallHandlerTest {

    @Mock
    private ISessionRepository repository;

    @Mock
    private ISessionPort port;

    @Mock
    private ICelEvaluationService celEvaluationService;

    private ToolsCallHandler handler;

    private final GovernancePrincipal principal = GovernancePrincipal.builder()
            .authType(GovernancePrincipal.AuthType.VIRTUAL_KEY)
            .virtualKeyId(42L)
            .build();

    @BeforeEach
    void setUp() {
        handler = new ToolsCallHandler();
        ReflectionTestUtils.setField(handler, "repository", repository);
        ReflectionTestUtils.setField(handler, "port", port);
        ReflectionTestUtils.setField(handler, "celEvaluationService", celEvaluationService);
        lenient().when(celEvaluationService.isToolAllowed(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
    }

    private McpSchemaVO.JSONRPCRequest callRequest(String toolName) {
        return new McpSchemaVO.JSONRPCRequest("2.0", "tools/call", 1,
                Map.of("name", toolName, "arguments", Map.of("orderId", "o-1")));
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
    @DisplayName("工具不存在 — JSON-RPC 错误码 -32003（TOOL_NOT_FOUND）")
    void unknownToolReturnsToolNotFound() throws Exception {
        when(repository.queryMcpGatewayProtocolConfig("gw-1", "ghost_tool")).thenReturn(null);

        McpSchemaVO.JSONRPCResponse response = handler.handle("gw-1", callRequest("ghost_tool"), principal);

        assertThat(response.error()).isNotNull();
        assertThat(response.error().code()).isEqualTo(McpErrorCodes.TOOL_NOT_FOUND);
        verify(port, never()).toolCall(any(), any());
    }

    @Test
    @DisplayName("CEL 拒绝 — JSON-RPC 错误码 -32006（无权限），与不存在区分")
    void celDeniedReturnsInsufficientPermissions() throws Exception {
        when(repository.queryMcpGatewayProtocolConfig("gw-1", "write_tool")).thenReturn(protocolConfig());
        when(celEvaluationService.isToolAllowed(eq(principal), eq("gw-1"), eq("tools/call"), eq("write_tool"),
                eq("PROTOCOL"))).thenReturn(false);

        McpSchemaVO.JSONRPCResponse response = handler.handle("gw-1", callRequest("write_tool"), principal);

        assertThat(response.error()).isNotNull();
        assertThat(response.error().code()).isEqualTo(McpErrorCodes.INSUFFICIENT_PERMISSIONS);
        assertThat(response.error().message()).contains("无权限");
        verify(port, never()).toolCall(any(), any());
    }

    @Test
    @DisplayName("CEL 放行 — 正常执行工具调用")
    void celAllowedExecutesToolCall() throws Exception {
        when(repository.queryMcpGatewayProtocolConfig("gw-1", "query_order")).thenReturn(protocolConfig());
        when(port.toolCall(any(), any())).thenReturn(Map.of("result", "ok"));

        McpSchemaVO.JSONRPCResponse response = handler.handle("gw-1", callRequest("query_order"), principal);

        assertThat(response.error()).isNull();
        assertThat(response.result()).isNotNull();
        verify(port).toolCall(any(), any());
    }
}

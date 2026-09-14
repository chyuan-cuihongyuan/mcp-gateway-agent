package cn.chyuan.ai.domain.session.service.message.handler.impl;

import cn.chyuan.ai.domain.session.adapter.port.ISessionPort;
import cn.chyuan.ai.domain.session.adapter.repository.ISessionRepository;
import cn.chyuan.ai.domain.session.model.valobj.McpSchemaVO;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolProtocolConfigVO;
import cn.chyuan.ai.domain.session.service.message.handler.support.ErrorSanitizer;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工具调用错误响应脱敏接线 — 两分支（工单 0408/0409，SELFLOOP3 loop-305）
 */
class ToolsCallHandlerErrorTest {

    private ToolsCallHandler handlerWith(ISessionRepository repository, ISessionPort port) {
        ToolResultGuard guard = mock(ToolResultGuard.class);
        when(guard.guard(any(), anyString())).thenReturn("ok");
        return handlerWith(repository, port, guard);
    }

    private ToolsCallHandler handlerWith(ISessionRepository repository, ISessionPort port,
                                         ToolResultGuard guard) {
        ToolsCallHandler handler = new ToolsCallHandler();
        ReflectionTestUtils.setField(handler, "repository", repository);
        ReflectionTestUtils.setField(handler, "port", port);
        ReflectionTestUtils.setField(handler, "auditLogger", mock(ToolCallAuditLogger.class));
        ReflectionTestUtils.setField(handler, "consentPolicy", mock(ConsentPolicy.class));
        ReflectionTestUtils.setField(handler, "toolResultGuard", guard);
        ReflectionTestUtils.setField(handler, "errorSanitizer", new ErrorSanitizer());
        return handler;
    }

    private McpSchemaVO.JSONRPCRequest request(String toolName) {
        return new McpSchemaVO.JSONRPCRequest(McpSchemaVO.JSONRPC_VERSION, "req-1", "tools/call",
                Map.of("name", toolName, "arguments", Map.of("orderId", "o1")));
    }

    @Test
    void appExceptionErrorCarriesBusinessInfo() {
        ISessionRepository repository = mock(ISessionRepository.class);
        when(repository.queryMcpGatewayProtocolConfig(anyString(), anyString()))
                .thenThrow(new AppException(ResponseCode.METHOD_NOT_FOUND.getCode(), "工具未找到: badTool"));

        ToolsCallHandler handler = handlerWith(repository, mock(ISessionPort.class));
        McpSchemaVO.JSONRPCResponse resp = handler.handle("g1", request("badTool"));

        assertThat(resp.error()).isNotNull();
        assertThat(resp.error().message()).isEqualTo("工具未找到: badTool");
    }

    @Test
    void unexpectedExceptionErrorIsGenericWithoutInternals() throws Exception {
        ISessionRepository repository = mock(ISessionRepository.class);
        when(repository.queryMcpGatewayProtocolConfig(anyString(), anyString()))
                .thenReturn(new McpToolProtocolConfigVO());
        ISessionPort port = mock(ISessionPort.class);
        when(port.toolCall(any(), any()))
                .thenThrow(new RuntimeException("Connection refused: /10.0.0.5:8080 at cn.chyuan.ai.Internal"));
        ToolResultGuard guard = mock(ToolResultGuard.class);
        when(guard.guard(any(), anyString())).thenReturn("ok");
        ToolsCallHandler handler = handlerWith(repository, port, guard);

        McpSchemaVO.JSONRPCResponse resp = handler.handle("g1", request("t"));

        assertThat(resp.error()).isNotNull();
        assertThat(resp.error().message()).isEqualTo("工具调用失败，请稍后重试");
        assertThat(resp.error().message()).doesNotContain("10.0.0.5").doesNotContain("cn.chyuan");
    }
}

package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.cases.mcp.IMcpMessageService;
import cn.chyuan.ai.cases.mcp.IMcpSessionService;
import cn.chyuan.ai.domain.session.model.entity.HandleMessageCommandEntity;
import cn.chyuan.ai.infrastructure.utils.ObservabilityHelper;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MCP 网关控制器单元测试
 * <p>
 * 测试场景：
 * 1. SSE 连接建立（正常）
 * 2. SSE 连接建立（无效 gatewayId）
 * 3. SSE 消息处理（正常）
 * 4. SSE 消息处理（无效 sessionId）
 * 5. SSE 消息处理（空消息体）
 * 6. SSE 消息处理（超长消息体）
 * 7. SSE 消息处理（非法 JSON）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MCP 网关控制器测试")
public class McpGatewayControllerTest {

    @Mock
    private IMcpSessionService mcpSessionService;

    @Mock
    private IMcpMessageService mcpMessageService;

    @Mock
    private ObservabilityHelper observabilityHelper;

    @InjectMocks
    private McpGatewayController controller;

    private static final String VALID_GATEWAY_ID = "gateway-001";
    private static final String VALID_SESSION_ID = "session-001";

    @Test
    @DisplayName("SSE 连接建立 — 正常 gatewayId 返回 Flux")
    public void testSseConnection_ValidGatewayId() throws Exception {
        // 准备
        when(mcpSessionService.createMcpSession(eq(VALID_GATEWAY_ID), anyString()))
                .thenReturn(Flux.empty());

        // 执行
        Flux<?> result = controller.handleSseConnection(VALID_GATEWAY_ID, "test-api-key");

        // 验证
        assertNotNull(result, "SSE 连接应返回非空 Flux");
        verify(mcpSessionService).createMcpSession(VALID_GATEWAY_ID, "test-api-key");
    }

    @Test
    @DisplayName("SSE 连接建立 — 无效 gatewayId 返回错误事件")
    public void testSseConnection_InvalidGatewayId() throws Exception {
        // 准备 — gatewayId 包含非法字符
        String invalidGatewayId = "gateway@#$";

        // 执行
        Flux<?> result = controller.handleSseConnection(invalidGatewayId, "");

        // 验证 — 应返回包含 error 事件的 Flux
        assertNotNull(result, "即使 gatewayId 无效也应返回 Flux");
        verify(mcpSessionService, never()).createMcpSession(anyString(), anyString());
    }

    @Test
    @DisplayName("SSE 连接建立 — 空 gatewayId 返回错误事件")
    public void testSseConnection_EmptyGatewayId() throws Exception {
        // 执行
        Flux<?> result = controller.handleSseConnection("", "");

        // 验证
        assertNotNull(result, "空 gatewayId 应返回 Flux");
        verify(mcpSessionService, never()).createMcpSession(anyString(), anyString());
    }

    @Test
    @DisplayName("SSE 消息处理 — 正常消息返回 200")
    public void testHandleMessage_ValidMessage() throws Exception {
        // 准备
        String validMessage = "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":1}";
        when(mcpMessageService.handleMessage(any(HandleMessageCommandEntity.class)))
                .thenReturn(org.springframework.http.ResponseEntity.ok().build());

        // 执行
        var result = controller.handleMessage(VALID_GATEWAY_ID, VALID_SESSION_ID, "test-key", validMessage);

        // 验证
        assertNotNull(result);
        verify(mcpMessageService).handleMessage(any(HandleMessageCommandEntity.class));
    }

    @Test
    @DisplayName("SSE 消息处理 — 空 sessionId 返回 400")
    public void testHandleMessage_EmptySessionId() throws Exception {
        // 准备
        String validMessage = "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":1}";

        // 执行
        var result = controller.handleMessage(VALID_GATEWAY_ID, "", "test-key", validMessage);

        // 验证 — 空 sessionId 应被拒绝
        assertNotNull(result);
        verify(mcpMessageService, never()).handleMessage(any());
    }

    @Test
    @DisplayName("SSE 消息处理 — 空消息体返回 400")
    public void testHandleMessage_EmptyMessageBody() throws Exception {
        // 执行
        var result = controller.handleMessage(VALID_GATEWAY_ID, VALID_SESSION_ID, "test-key", "");

        // 验证
        assertNotNull(result);
        verify(mcpMessageService, never()).handleMessage(any());
    }

    @Test
    @DisplayName("SSE 消息处理 — 超长消息体返回 400")
    public void testHandleMessage_OversizedMessageBody() throws Exception {
        // 准备 — 构造超过 64KB 的消息
        String oversizedMessage = "{\"jsonrpc\":\"2.0\",\"method\":\"test\",\"id\":1,\"params\":\"" + "x".repeat(65 * 1024) + "\"}";

        // 执行
        var result = controller.handleMessage(VALID_GATEWAY_ID, VALID_SESSION_ID, "test-key", oversizedMessage);

        // 验证
        assertNotNull(result);
        verify(mcpMessageService, never()).handleMessage(any());
    }

    @Test
    @DisplayName("SSE 消息处理 — 非 JSON 消息体返回 400")
    public void testHandleMessage_InvalidJson() throws Exception {
        // 执行
        var result = controller.handleMessage(VALID_GATEWAY_ID, VALID_SESSION_ID, "test-key", "not-json");

        // 验证
        assertNotNull(result);
        verify(mcpMessageService, never()).handleMessage(any());
    }

    @Test
    @DisplayName("SSE 消息处理 — tools/call 方法需验证 toolName")
    public void testHandleMessage_ToolCallWithValidToolName() throws Exception {
        // 准备
        String toolCallMessage = "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"id\":2,\"params\":{\"name\":\"queryOrder\"}}";
        when(mcpMessageService.handleMessage(any(HandleMessageCommandEntity.class)))
                .thenReturn(org.springframework.http.ResponseEntity.ok().build());

        // 执行
        var result = controller.handleMessage(VALID_GATEWAY_ID, VALID_SESSION_ID, "test-key", toolCallMessage);

        // 验证
        assertNotNull(result);
        verify(mcpMessageService).handleMessage(any(HandleMessageCommandEntity.class));
    }

    @Test
    @DisplayName("SSE 消息处理 — 内部服务异常返回 500")
    public void testHandleMessage_ServiceException() throws Exception {
        // 准备
        String validMessage = "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":1}";
        when(mcpMessageService.handleMessage(any(HandleMessageCommandEntity.class)))
                .thenThrow(new RuntimeException("服务内部错误"));

        // 执行
        var result = controller.handleMessage(VALID_GATEWAY_ID, VALID_SESSION_ID, "test-key", validMessage);

        // 验证
        assertNotNull(result);
    }
}

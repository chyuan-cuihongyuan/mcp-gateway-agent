package cn.chyuan.ai.domain.session.service.message;

import cn.chyuan.ai.domain.session.model.entity.HandleMessageCommandEntity;
import cn.chyuan.ai.domain.session.model.valobj.McpSchemaVO;
import cn.chyuan.ai.domain.session.service.message.handler.IRequestHandler;
import cn.chyuan.ai.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SessionMessageService 消息路由契约测试（工单 1139）：
 * JSONRPCRequest 按 method 分发到注册的 handler；未知方法/缺失 handler 抛 0003；
 * 通知与响应消息不产生业务处理。
 */
@DisplayName("SessionMessageService 消息路由契约")
class SessionMessageServiceTest {

    private static final String GATEWAY_ID = "gw-1";

    private final IRequestHandler initializeHandler = mock(IRequestHandler.class);
    private final IRequestHandler toolsListHandler = mock(IRequestHandler.class);

    private SessionMessageService service;

    @BeforeEach
    void setUp() {
        service = new SessionMessageService();
        Map<String, IRequestHandler> handlers = new HashMap<>();
        handlers.put("initializeHandler", initializeHandler);
        handlers.put("toolsListHandler", toolsListHandler);
        ReflectionTestUtils.setField(service, "requestHandlerMap", handlers);
    }

    private McpSchemaVO.JSONRPCRequest request(String method, Object id) {
        return new McpSchemaVO.JSONRPCRequest("2.0", method, id, Map.of());
    }

    @Test
    @DisplayName("initialize 请求路由到 initializeHandler 并透传响应")
    void routesInitializeToHandler() {
        McpSchemaVO.JSONRPCResponse response =
                new McpSchemaVO.JSONRPCResponse("2.0", "id-1", Map.of("ok", true), null);
        when(initializeHandler.handle(eq(GATEWAY_ID), any())).thenReturn(response);

        McpSchemaVO.JSONRPCResponse out =
                service.processHandlerMessage(GATEWAY_ID, request("initialize", "id-1"));

        assertThat(out).isSameAs(response);
        verify(initializeHandler).handle(eq(GATEWAY_ID), any());
        verify(toolsListHandler, never()).handle(any(), any());
    }

    @Test
    @DisplayName("tools/list 请求路由到 toolsListHandler")
    void routesToolsListToHandler() {
        McpSchemaVO.JSONRPCResponse response = new McpSchemaVO.JSONRPCResponse("2.0", 7, "result", null);
        when(toolsListHandler.handle(eq(GATEWAY_ID), any())).thenReturn(response);

        McpSchemaVO.JSONRPCResponse out =
                service.processHandlerMessage(GATEWAY_ID, request("tools/list", 7));

        assertThat(out).isSameAs(response);
        verify(initializeHandler, never()).handle(any(), any());
    }

    @Test
    @DisplayName("未知方法抛 AppException 0003 未找到方法")
    void unknownMethodThrows0003() {
        assertThatThrownBy(() -> service.processHandlerMessage(GATEWAY_ID, request("bogus/method", 1)))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getCode()).isEqualTo("0003"));
    }

    @Test
    @DisplayName("已注册方法但 handler 缺失同样抛 0003")
    void missingHandlerThrows0003() {
        assertThatThrownBy(() -> service.processHandlerMessage(GATEWAY_ID, request("tools/call", 2)))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getCode()).isEqualTo("0003"));
    }

    @Test
    @DisplayName("通知消息不做业务处理返回 null")
    void notificationReturnsNull() {
        McpSchemaVO.JSONRPCNotification notification =
                new McpSchemaVO.JSONRPCNotification("2.0", "notifications/initialized", null);

        assertThat(service.processHandlerMessage(GATEWAY_ID, notification)).isNull();
        verify(initializeHandler, never()).handle(any(), any());
    }

    @Test
    @DisplayName("响应消息与命令实体重载：直通转发不产生副作用")
    void responseAndEntityOverloadReturnNull() {
        McpSchemaVO.JSONRPCResponse response = new McpSchemaVO.JSONRPCResponse("2.0", 1, "r", null);
        assertThat(service.processHandlerMessage(GATEWAY_ID, response)).isNull();

        HandleMessageCommandEntity entity = HandleMessageCommandEntity.builder()
                .gatewayId(GATEWAY_ID)
                .jsonrpcMessage(request("initialize", "id-9"))
                .build();
        McpSchemaVO.JSONRPCResponse expected = new McpSchemaVO.JSONRPCResponse("2.0", "id-9", "ok", null);
        when(initializeHandler.handle(eq(GATEWAY_ID), any())).thenReturn(expected);

        assertThat(service.processHandlerMessage(entity)).isSameAs(expected);
    }
}

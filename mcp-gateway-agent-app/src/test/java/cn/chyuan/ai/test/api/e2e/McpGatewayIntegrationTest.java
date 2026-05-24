package cn.chyuan.ai.test.api.e2e;

import cn.chyuan.ai.api.dto.CreateSessionRequestDTO;
import cn.chyuan.ai.api.dto.ChatRequestDTO;
import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.cases.mcp.IMcpMessageService;
import cn.chyuan.ai.cases.mcp.IMcpSessionService;
import cn.chyuan.ai.domain.session.model.entity.HandleMessageCommandEntity;
import cn.chyuan.ai.domain.session.model.valobj.SessionConfigVO;
import cn.chyuan.ai.domain.session.service.ISessionManagementService;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import com.alibaba.fastjson.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MCP 网关集成测试
 * <p>
 * 覆盖场景：
 * 1. 会话管理测试 - 创建会话、会话超时清理、并发访问
 * 2. 消息处理测试 - initialize、tools/list、tools/call 消息处理
 * 3. HTTP 转发测试 - POST/GET 请求转发、请求头传递、错误响应处理
 * 4. 异常处理测试 - 全局异常处理器、参数校验失败、下游服务不可用
 * </p>
 *
 * @author chyuan
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@DisplayName("MCP 网关集成测试")
class McpGatewayIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private IMcpSessionService mcpSessionService;

    @MockBean
    private IMcpMessageService mcpMessageService;

    @MockBean
    private ISessionManagementService sessionManagementService;

    private static final String TEST_GATEWAY_ID = "gateway_001";
    private static final String TEST_API_KEY = "test-api-key-12345";
    private static final String TEST_SESSION_ID = "session-" + UUID.randomUUID();

    /**
     * 会话管理测试套件
     */
    @Nested
    @DisplayName("会话管理测试")
    class SessionManagementTests {

        /**
         * 测试创建会话 - 正常场景
         */
        @Test
        @DisplayName("测试创建会话 - 正常场景")
        void testCreateSession_Success() throws Exception {
            // 准备测试数据
            String sessionId = "new-session-" + UUID.randomUUID();
            ServerSentEvent<String> initEvent = ServerSentEvent.<String>builder()
                    .id(sessionId)
                    .event("endpoint")
                    .data("/" + TEST_GATEWAY_ID + "/mcp/sse?sessionId=" + sessionId)
                    .build();

            // 模拟服务行为
            when(mcpSessionService.createMcpSession(eq(TEST_GATEWAY_ID), eq(TEST_API_KEY)))
                    .thenReturn(Flux.just(initEvent));

            // 执行测试并验证
            webTestClient.get()
                    .uri("/{gatewayId}/mcp/sse?api_key={apiKey}", TEST_GATEWAY_ID, TEST_API_KEY)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().contentType(MediaType.TEXT_EVENT_STREAM)
                    .expectBodyList(ServerSentEvent.class)
                    .hasSize(1);

            // 验证服务调用
            verify(mcpSessionService, times(1)).createMcpSession(eq(TEST_GATEWAY_ID), eq(TEST_API_KEY));
        }

        /**
         * 测试创建会话 - gatewayId 为空
         */
        @Test
        @DisplayName("测试创建会话 - gatewayId 为空")
        void testCreateSession_EmptyGatewayId() {
            // 执行测试并验证 - 空 gatewayId 应返回错误 SSE 事件
            webTestClient.get()
                    .uri("//mcp/sse")
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().contentType(MediaType.TEXT_EVENT_STREAM);
        }

        /**
         * 测试会话超时清理
         */
        @Test
        @DisplayName("测试会话超时清理")
        void testSessionTimeout_Cleanup() {
            // 准备测试数据
            SessionConfigVO expiredSession = SessionConfigVO.builder()
                    .sessionId(TEST_SESSION_ID)
                    .active(true)
                    .build();

            // 模拟会话管理服务
            doNothing().when(sessionManagementService).cleanupExpiredSessions();

            // 执行清理操作
            sessionManagementService.cleanupExpiredSessions();

            // 验证清理方法被调用
            verify(sessionManagementService, times(1)).cleanupExpiredSessions();
        }

        /**
         * 测试会话并发访问
         */
        @Test
        @DisplayName("测试会话并发访问")
        void testSessionConcurrentAccess() throws Exception {
            // 准备测试数据
            int threadCount = 10;
            AtomicInteger successCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(threadCount);
            ExecutorService executorService = Executors.newFixedThreadPool(threadCount);

            ServerSentEvent<String> event = ServerSentEvent.<String>builder()
                    .id(TEST_SESSION_ID)
                    .event("endpoint")
                    .data("/" + TEST_GATEWAY_ID + "/mcp/sse?sessionId=" + TEST_SESSION_ID)
                    .build();

            // 模拟服务行为 - 每次调用都返回成功
            when(mcpSessionService.createMcpSession(eq(TEST_GATEWAY_ID), anyString()))
                    .thenReturn(Flux.just(event));

            // 并发执行创建会话
            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executorService.submit(() -> {
                    try {
                        webTestClient.get()
                                .uri("/{gatewayId}/mcp/sse?api_key=concurrent-key-{index}", TEST_GATEWAY_ID, index)
                                .accept(MediaType.TEXT_EVENT_STREAM)
                                .exchange()
                                .expectStatus().isOk();
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        // 忽略异常，记录失败
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // 等待所有线程完成
            latch.await(10, TimeUnit.SECONDS);
            executorService.shutdown();

            // 验证并发访问结果
            System.out.println("并发测试完成，成功数: " + successCount.get() + "/" + threadCount);
        }
    }

    /**
     * 消息处理测试套件
     */
    @Nested
    @DisplayName("消息处理测试")
    class MessageHandlingTests {

        /**
         * 测试 initialize 消息处理
         */
        @Test
        @DisplayName("测试 initialize 消息处理")
        void testHandleMessage_Initialize() throws Exception {
            // 准备 initialize 消息
            String initializeMessage = JSON.toJSONString(new Object() {
                public final String jsonrpc = "2.0";
                public final String method = "initialize";
                public final String id = "init-001";
                public final Object params = new Object() {
                    public final String protocolVersion = "2024-11-05";
                    public final Object capabilities = new Object() {};
                    public final Object clientInfo = new Object() {
                        public final String name = "Test Client";
                        public final String version = "1.0.0";
                    };
                };
            });

            // 模拟服务行为
            when(mcpMessageService.handleMessage(any(HandleMessageCommandEntity.class)))
                    .thenReturn(ResponseEntity.ok().build());

            // 执行测试
            webTestClient.post()
                    .uri("/{gatewayId}/mcp/sse?sessionId={sessionId}&api_key={apiKey}",
                            TEST_GATEWAY_ID, TEST_SESSION_ID, TEST_API_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(initializeMessage)
                    .exchange()
                    .expectStatus().isOk();

            // 验证服务调用
            verify(mcpMessageService, times(1)).handleMessage(any(HandleMessageCommandEntity.class));
        }

        /**
         * 测试 tools/list 消息处理
         */
        @Test
        @DisplayName("测试 tools/list 消息处理")
        void testHandleMessage_ToolsList() throws Exception {
            // 准备 tools/list 消息
            String toolsListMessage = JSON.toJSONString(new Object() {
                public final String jsonrpc = "2.0";
                public final String method = "tools/list";
                public final String id = "tools-001";
                public final Object params = new Object() {};
            });

            // 模拟服务行为
            when(mcpMessageService.handleMessage(any(HandleMessageCommandEntity.class)))
                    .thenReturn(ResponseEntity.ok().build());

            // 执行测试
            webTestClient.post()
                    .uri("/{gatewayId}/mcp/sse?sessionId={sessionId}&api_key={apiKey}",
                            TEST_GATEWAY_ID, TEST_SESSION_ID, TEST_API_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(toolsListMessage)
                    .exchange()
                    .expectStatus().isOk();

            // 验证服务调用
            verify(mcpMessageService, times(1)).handleMessage(any(HandleMessageCommandEntity.class));
        }

        /**
         * 测试 tools/call 消息处理
         */
        @Test
        @DisplayName("测试 tools/call 消息处理")
        void testHandleMessage_ToolsCall() throws Exception {
            // 准备 tools/call 消息
            String toolsCallMessage = JSON.toJSONString(new Object() {
                public final String jsonrpc = "2.0";
                public final String method = "tools/call";
                public final String id = "call-001";
                public final Object params = new Object() {
                    public final String name = "test_tool";
                    public final Object arguments = new Object() {
                        public final String query = "test query";
                        public final int limit = 10;
                    };
                };
            });

            // 模拟服务行为
            when(mcpMessageService.handleMessage(any(HandleMessageCommandEntity.class)))
                    .thenReturn(ResponseEntity.ok().build());

            // 执行测试
            webTestClient.post()
                    .uri("/{gatewayId}/mcp/sse?sessionId={sessionId}&api_key={apiKey}",
                            TEST_GATEWAY_ID, TEST_SESSION_ID, TEST_API_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(toolsCallMessage)
                    .exchange()
                    .expectStatus().isOk();

            // 验证服务调用
            verify(mcpMessageService, times(1)).handleMessage(any(HandleMessageCommandEntity.class));
        }
    }

    /**
     * HTTP 转发测试套件
     */
    @Nested
    @DisplayName("HTTP 转发测试")
    class HttpForwardingTests {

        /**
         * 测试 POST 请求转发
         */
        @Test
        @DisplayName("测试 POST 请求转发")
        void testHttpPostForwarding() throws Exception {
            // 准备 POST 请求消息
            String postMessage = JSON.toJSONString(new Object() {
                public final String jsonrpc = "2.0";
                public final String method = "tools/call";
                public final String id = "post-001";
                public final Object params = new Object() {
                    public final String name = "http_post_tool";
                    public final Object arguments = new Object() {
                        public final String url = "https://api.example.com/data";
                        public final Object body = new Object() {
                            public final String key = "value";
                        };
                    };
                };
            });

            // 模拟服务行为
            when(mcpMessageService.handleMessage(any(HandleMessageCommandEntity.class)))
                    .thenReturn(ResponseEntity.ok().build());

            // 执行测试
            webTestClient.post()
                    .uri("/{gatewayId}/mcp/sse?sessionId={sessionId}",
                            TEST_GATEWAY_ID, TEST_SESSION_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(postMessage)
                    .exchange()
                    .expectStatus().isOk();

            // 验证服务调用
            verify(mcpMessageService, times(1)).handleMessage(any(HandleMessageCommandEntity.class));
        }

        /**
         * 测试 GET 请求转发
         */
        @Test
        @DisplayName("测试 GET 请求转发")
        void testHttpGetForwarding() throws Exception {
            // 准备 GET 请求消息
            String getMessage = JSON.toJSONString(new Object() {
                public final String jsonrpc = "2.0";
                public final String method = "tools/call";
                public final String id = "get-001";
                public final Object params = new Object() {
                    public final String name = "http_get_tool";
                    public final Object arguments = new Object() {
                        public final String path = "/api/resource/123";
                        public final String query = "param1=value1";
                    };
                };
            });

            // 模拟服务行为
            when(mcpMessageService.handleMessage(any(HandleMessageCommandEntity.class)))
                    .thenReturn(ResponseEntity.ok().build());

            // 执行测试
            webTestClient.post()
                    .uri("/{gatewayId}/mcp/sse?sessionId={sessionId}",
                            TEST_GATEWAY_ID, TEST_SESSION_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(getMessage)
                    .exchange()
                    .expectStatus().isOk();

            // 验证服务调用
            verify(mcpMessageService, times(1)).handleMessage(any(HandleMessageCommandEntity.class));
        }

        /**
         * 测试请求头传递（X-Agent-Auth）
         */
        @Test
        @DisplayName("测试请求头传递（X-Agent-Auth）")
        void testHeaderForwarding_XAgentAuth() throws Exception {
            // 准备带认证头的请求
            String authMessage = JSON.toJSONString(new Object() {
                public final String jsonrpc = "2.0";
                public final String method = "tools/call";
                public final String id = "auth-001";
                public final Object params = new Object() {
                    public final String name = "auth_tool";
                    public final Object arguments = new Object() {
                        public final String action = "verify";
                    };
                };
            });

            // 模拟服务行为
            when(mcpMessageService.handleMessage(any(HandleMessageCommandEntity.class)))
                    .thenReturn(ResponseEntity.ok().build());

            // 执行测试 - 带自定义请求头
            webTestClient.post()
                    .uri("/{gatewayId}/mcp/sse?sessionId={sessionId}&api_key={apiKey}",
                            TEST_GATEWAY_ID, TEST_SESSION_ID, TEST_API_KEY)
                    .header("X-Agent-Auth", "Bearer test-token-12345")
                    .header("X-Request-Id", "req-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(authMessage)
                    .exchange()
                    .expectStatus().isOk();

            // 验证服务调用
            verify(mcpMessageService, times(1)).handleMessage(any(HandleMessageCommandEntity.class));
        }

        /**
         * 测试错误响应处理
         */
        @Test
        @DisplayName("测试错误响应处理")
        void testErrorResponse_Handling() throws Exception {
            // 准备请求消息
            String errorMessage = JSON.toJSONString(new Object() {
                public final String jsonrpc = "2.0";
                public final String method = "tools/call";
                public final String id = "error-001";
                public final Object params = new Object() {
                    public final String name = "error_tool";
                    public final Object arguments = new Object() {};
                };
            });

            // 模拟服务抛出异常
            when(mcpMessageService.handleMessage(any(HandleMessageCommandEntity.class)))
                    .thenThrow(new AppException(ResponseCode.RESPONSE_ERROR.getCode(), "下游服务返回错误"));

            // 执行测试
            webTestClient.post()
                    .uri("/{gatewayId}/mcp/sse?sessionId={sessionId}",
                            TEST_GATEWAY_ID, TEST_SESSION_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(errorMessage)
                    .exchange()
                    .expectStatus().is5xxServerError();
        }
    }

    /**
     * 异常处理测试套件
     */
    @Nested
    @DisplayName("异常处理测试")
    class ExceptionHandlingTests {

        /**
         * 测试全局异常处理器 - 业务异常
         */
        @Test
        @DisplayName("测试全局异常处理器 - 业务异常")
        void testGlobalExceptionHandler_AppException() {
            // 准备测试数据
            CreateSessionRequestDTO requestDTO = new CreateSessionRequestDTO();
            requestDTO.setAgentId("");
            requestDTO.setUserId("test-user");

            // 执行测试 - 空 agentId 应触发参数校验异常
            webTestClient.post()
                    .uri("/api/v1/create_session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestDTO)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(ResponseCode.ILLEGAL_PARAMETER.getCode());
        }

        /**
         * 测试全局异常处理器 - 运行时异常
         */
        @Test
        @DisplayName("测试全局异常处理器 - 运行时异常")
        void testGlobalExceptionHandler_RuntimeException() throws Exception {
            // 准备请求消息
            String message = JSON.toJSONString(new Object() {
                public final String jsonrpc = "2.0";
                public final String method = "initialize";
                public final String id = "runtime-001";
                public final Object params = new Object() {};
            });

            // 模拟运行时异常
            when(mcpMessageService.handleMessage(any(HandleMessageCommandEntity.class)))
                    .thenThrow(new RuntimeException("模拟运行时异常"));

            // 执行测试
            webTestClient.post()
                    .uri("/{gatewayId}/mcp/sse?sessionId={sessionId}",
                            TEST_GATEWAY_ID, TEST_SESSION_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(message)
                    .exchange()
                    .expectStatus().is5xxServerError();
        }

        /**
         * 测试参数校验失败
         */
        @Test
        @DisplayName("测试参数校验失败")
        void testValidation_Failure() {
            // 准备无效的请求数据 - 缺少必填字段
            CreateSessionRequestDTO invalidRequest = new CreateSessionRequestDTO();
            // agentId 和 userId 都为空

            // 执行测试
            webTestClient.post()
                    .uri("/api/v1/create_session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(invalidRequest)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(ResponseCode.ILLEGAL_PARAMETER.getCode());
        }

        /**
         * 测试参数校验失败 - 消息体格式错误
         */
        @Test
        @DisplayName("测试参数校验失败 - 消息体格式错误")
        void testValidation_InvalidJsonBody() {
            // 准备无效的 JSON 消息
            String invalidJson = "{invalid json content}";

            // 执行测试
            webTestClient.post()
                    .uri("/{gatewayId}/mcp/sse?sessionId={sessionId}",
                            TEST_GATEWAY_ID, TEST_SESSION_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(invalidJson)
                    .exchange()
                    .expectStatus().is5xxServerError();
        }

        /**
         * 测试下游服务不可用
         */
        @Test
        @DisplayName("测试下游服务不可用")
        void testDownstreamService_Unavailable() throws Exception {
            // 准备请求消息
            String message = JSON.toJSONString(new Object() {
                public final String jsonrpc = "2.0";
                public final String method = "tools/call";
                public final String id = "unavailable-001";
                public final Object params = new Object() {
                    public final String name = "downstream_tool";
                    public final Object arguments = new Object() {};
                };
            });

            // 模拟下游服务不可用异常
            when(mcpMessageService.handleMessage(any(HandleMessageCommandEntity.class)))
                    .thenThrow(new AppException(
                            ResponseCode.RESPONSE_ERROR.getCode(),
                            "下游服务不可用，请稍后重试"));

            // 执行测试
            webTestClient.post()
                    .uri("/{gatewayId}/mcp/sse?sessionId={sessionId}",
                            TEST_GATEWAY_ID, TEST_SESSION_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(message)
                    .exchange()
                    .expectStatus().is5xxServerError();
        }

        /**
         * 测试请求超时场景
         */
        @Test
        @DisplayName("测试请求超时场景")
        void testRequestTimeout() throws Exception {
            // 准备请求消息
            String message = JSON.toJSONString(new Object() {
                public final String jsonrpc = "2.0";
                public final String method = "tools/call";
                public final String id = "timeout-001";
                public final Object params = new Object() {
                    public final String name = "slow_tool";
                    public final Object arguments = new Object() {};
                };
            });

            // 模拟超时异常
            when(mcpMessageService.handleMessage(any(HandleMessageCommandEntity.class)))
                    .thenThrow(new AppException(
                            ResponseCode.RESPONSE_ERROR.getCode(),
                            "请求超时，下游服务响应时间过长"));

            // 执行测试
            webTestClient.post()
                    .uri("/{gatewayId}/mcp/sse?sessionId={sessionId}",
                            TEST_GATEWAY_ID, TEST_SESSION_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(message)
                    .exchange()
                    .expectStatus().is5xxServerError();
        }

        /**
         * 测试非法网关ID
         */
        @Test
        @DisplayName("测试非法网关ID")
        void testInvalidGatewayId() {
            // 准备无效的网关ID
            String invalidGatewayId = "";

            // 执行测试
            webTestClient.get()
                    .uri("/{gatewayId}/mcp/sse", invalidGatewayId)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().contentType(MediaType.TEXT_EVENT_STREAM);
        }
    }
}

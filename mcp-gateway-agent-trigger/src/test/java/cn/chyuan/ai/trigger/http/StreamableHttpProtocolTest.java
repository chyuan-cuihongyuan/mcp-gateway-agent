package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.domain.governance.adapter.repository.ICelRuleRepository;
import cn.chyuan.ai.domain.governance.model.valobj.CelRuleVO;
import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import cn.chyuan.ai.domain.governance.service.CelEvaluationService;
import cn.chyuan.ai.domain.governance.service.CelRuleService;
import cn.chyuan.ai.domain.governance.service.IAuditService;
import cn.chyuan.ai.domain.governance.service.IGovernanceAuthService;
import cn.chyuan.ai.domain.governance.service.IQuotaService;
import cn.chyuan.ai.domain.session.adapter.port.ISessionPort;
import cn.chyuan.ai.domain.session.adapter.repository.ISessionRepository;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpGatewayConfigVO;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolConfigVO;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolProtocolConfigVO;
import cn.chyuan.ai.domain.session.service.tool.IMcpToolCatalogService;
import cn.chyuan.ai.domain.session.service.tool.IMcpToolInvocationService;
import cn.chyuan.ai.domain.session.service.tool.McpToolCatalogService;
import cn.chyuan.ai.domain.session.service.tool.McpToolInvocationService;
import cn.chyuan.ai.infrastructure.gateway.streamable.GatewayMcpServerRegistry;
import cn.chyuan.ai.infrastructure.utils.ObservabilityHelper;
import cn.chyuan.ai.observability.client.ObservabilityClient;
import cn.chyuan.ai.trigger.filter.GovernanceAuthFilter;
import cn.chyuan.ai.trigger.filter.QuotaEnforcementFilter;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import cn.chyuan.ai.types.exception.AppException;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.servlet.ServletWebServerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Streamable HTTP 协议级全链路集成测试（工单 0020 主缝合点）
 *
 * <p>真实 MCP Java SDK 客户端（HttpClientStreamableHttpTransport）打随机端口上的
 * /api-gateway/{gatewayId}/mcp：initialize 握手 → tools/list（CEL 过滤）→
 * tools/call（三种下游来源各一例 + -32006/-32003）→ 无凭证 401 → 超配额 429 →
 * 未知会话 404 → 旧 SSE 端点 404 → 观测上报接续。
 *
 * <p>切片上下文：servlet/registry/官方传输/治理过滤器/CEL 引擎全真；
 * 仓储/端口/认证/配额用进程内假实现（认证与配额的行为在 0017/0019 单测覆盖）。
 * 命名 *Test 以纳入 surefire 默认执行（无 failsafe 配置）。
 *
 * @author chyuan
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = StreamableHttpProtocolTest.TestApp.class,
        properties = {
                "mcp.session.timeout-minutes=30",
                "mcp.server.request-timeout-ms=60000",
                "mcp.server.tool-spec-refresh-seconds=300",
                "governance.cache.ttl-seconds=1"
        })
class StreamableHttpProtocolTest {

    private static final String GATEWAY_ID = "gateway_business";
    private static final String VALID_KEY = "vk-test-key";

    @LocalServerPort
    private int port;

    private String endpoint() {
        return "http://127.0.0.1:" + port + "/api-gateway/" + GATEWAY_ID + "/mcp";
    }

    @BeforeEach
    void resetFakes() {
        FakeQuotaService.deny = false;
    }

    private McpSyncClient mcpClient() {
        return McpClient.sync(HttpClientStreamableHttpTransport
                        .builder("http://127.0.0.1:" + port)
                        .endpoint("/api-gateway/" + GATEWAY_ID + "/mcp")
                        .httpRequestCustomizer((requestBuilder, method, uri, body, context) ->
                                requestBuilder.header("Authorization", "Bearer " + VALID_KEY))
                        .build())
                .requestTimeout(Duration.ofSeconds(10))
                .clientInfo(new McpSchema.Implementation("protocol-it-client", "1.0.0"))
                .build();
    }

    // ------------------------------------------------------------------
    // 主链路：initialize → tools/list（CEL）→ tools/call（三来源）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("全链路：握手 → CEL 过滤的清单 → 三来源调用 → 治理拒绝码区分")
    void fullChainInitializeListAndCall() {
        try (McpSyncClient client = mcpClient()) {
            // 1. initialize：官方传输建会话，serverInfo 来自网关配置
            McpSchema.InitializeResult init = client.initialize();
            assertThat(init.serverInfo().name()).isEqualTo("gateway-business-test");

            // 2. tools/list：CEL 规则不放行的 secret_tool 隐藏，三来源工具可见
            McpSchema.ListToolsResult tools = client.listTools();
            assertThat(tools.tools()).extracting(McpSchema.Tool::name)
                    .containsExactlyInAnyOrder("business_order_query", "local_report_generator",
                            "external_weather_lookup");

            // 3. tools/call：三来源各一例（协议映射 HTTP 调用，下游以假端口区分）
            assertCallOk(client, "business_order_query");
            assertCallOk(client, "local_report_generator");
            assertCallOk(client, "external_weather_lookup");

            // 4. CEL 拒绝（清单外工具直呼）：-32006 无权限
            assertThatThrownBy(() -> client.callTool(
                    new McpSchema.CallToolRequest("secret_tool", Map.of("orderId", "o-1"))))
                    .isInstanceOf(McpError.class)
                    .satisfies(e -> assertThat(((McpError) e).getJsonRpcError().code())
                            .isEqualTo(McpErrorCodes.INSUFFICIENT_PERMISSIONS));

            // 5. 未知工具：-32003 不存在（与无权限区分）
            assertThatThrownBy(() -> client.callTool(
                    new McpSchema.CallToolRequest("ghost_tool", Map.of("orderId", "o-1"))))
                    .isInstanceOf(McpError.class)
                    .satisfies(e -> assertThat(((McpError) e).getJsonRpcError().code())
                            .isEqualTo(McpErrorCodes.TOOL_NOT_FOUND));
        }

        // 6. 观测接续：新端点的工具调用产生 trace 上报
        verify(TestApp.OBSERVABILITY_CLIENT_MOCK, atLeastOnce()).reportToolCall(any());
    }

    private void assertCallOk(McpSyncClient client, String toolName) {
        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest(toolName, Map.of("orderId", "o-1")));
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).isNotEmpty();
        assertThat(result.content().get(0).toString()).contains("payload-from-" + toolName);
    }

    // ------------------------------------------------------------------
    // 治理拒绝场景（协议面 HTTP 状态码 + JSON-RPC 结构化错误）
    // ------------------------------------------------------------------

    private static final String JSONRPC_INIT = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
            + "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"it\",\"version\":\"1.0\"}}}";

    private HttpResponse<String> rawPostForStatus(String url, String body, String authorization)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("无凭证 — HTTP 401 + JSON-RPC -32008")
    void noCredentialReturns401() {
        HttpResponse<String> response = rawUnchecked(() -> rawPostForStatus(endpoint(), JSONRPC_INIT, null));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains(String.valueOf(McpErrorCodes.AUTH_REQUIRED));
    }

    @Test
    @DisplayName("错误凭证 — HTTP 403 + JSON-RPC -32006 域认证拒绝")
    void wrongCredentialReturns403() {
        HttpResponse<String> response = rawUnchecked(() ->
                rawPostForStatus(endpoint(), JSONRPC_INIT, "Bearer vk-wrong-key"));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains(String.valueOf(McpErrorCodes.INSUFFICIENT_PERMISSIONS));
    }

    @Test
    @DisplayName("超配额 — HTTP 429 + Retry-After + JSON-RPC -32009 与剩余额度")
    void quotaExceededReturns429() {
        FakeQuotaService.deny = true;
        HttpResponse<String> response = rawUnchecked(() ->
                rawPostForStatus(endpoint(), JSONRPC_INIT, "Bearer " + VALID_KEY));

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue("Retry-After")).contains("7");
        assertThat(response.body()).contains(String.valueOf(McpErrorCodes.QUOTA_EXCEEDED));
        assertThat(response.body()).contains("remaining");
    }

    @Test
    @DisplayName("未知/过期会话 — HTTP 404（委派层会话把关）")
    void unknownSessionReturns404() {
        HttpResponse<String> response = rawUnchecked(() -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header("Authorization", "Bearer " + VALID_KEY)
                    .header("Mcp-Session-Id", "00000000-dead-beef-0000-000000000000")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"))
                    .build();
            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        });

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("Session not found or expired");
    }

    @Test
    @DisplayName("旧 SSE 端点 — 404（下线，无兼容期）")
    void legacySseEndpointReturns404() {
        HttpResponse<String> response = rawUnchecked(() ->
                HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(
                                        "http://127.0.0.1:" + port + "/api-gateway/" + GATEWAY_ID + "/mcp/sse"))
                                .header("Authorization", "Bearer " + VALID_KEY)
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString()));

        assertThat(response.statusCode()).isEqualTo(404);
    }

    private <T> T rawUnchecked(ThrowingSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    // ------------------------------------------------------------------
    // 切片上下文：治理链与官方传输全真，仓储/端口/认证/配额为假实现
    // ------------------------------------------------------------------

    @SpringBootConfiguration
    static class TestApp {

        static ObservabilityClient OBSERVABILITY_CLIENT_MOCK = mock(ObservabilityClient.class);

        /** 最小上下文缺占位符解析器，@Value 会拿到字面量——显式注册 */
        @Bean
        static PropertySourcesPlaceholderConfigurer placeholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        ServletWebServerFactory servletWebServerFactory() {
            return new TomcatServletWebServerFactory();
        }

        // ---- 假实现 ----

        @Bean
        ISessionRepository sessionRepository() {
            return new FakeSessionRepository();
        }

        @Bean
        ISessionPort sessionPort() {
            return (httpConfig, params) -> "payload-from-" + extractToolName(httpConfig.getHttpUrl());
        }

        @Bean
        IGovernanceAuthService governanceAuthService() {
            return new FakeGovernanceAuthService();
        }

        @Bean
        IQuotaService quotaService() {
            return new FakeQuotaService();
        }

        @Bean
        ICelRuleRepository celRuleRepository() {
            return new FakeCelRuleRepository();
        }

        @Bean
        IAuditService auditService() {
            return new IAuditService() {
                @Override
                public void record(cn.chyuan.ai.domain.governance.model.entity.AuditCommandEntity entity) {
                    // no-op
                }

                @Override
                public List<cn.chyuan.ai.domain.governance.adapter.repository.IAuditLogRepository.AuditLogVO> page(
                        String resourceType, String resourceId, int page, int size) {
                    return List.of();
                }

                @Override
                public long count(String resourceType, String resourceId) {
                    return 0;
                }
            };
        }

        // ---- 真实治理与传输链 ----

        @Bean
        CelRuleService celRuleService(ICelRuleRepository celRuleRepository, IAuditService auditService) {
            CelRuleService service = new CelRuleService();
            ReflectionTestUtils.setField(service, "repository", celRuleRepository);
            ReflectionTestUtils.setField(service, "auditService", auditService);
            ReflectionTestUtils.setField(service, "cacheTtlSeconds", 1L);
            service.init();
            return service;
        }

        @Bean
        CelEvaluationService celEvaluationService(CelRuleService celRuleService) {
            CelEvaluationService service = new CelEvaluationService();
            ReflectionTestUtils.setField(service, "celRuleService", celRuleService);
            return service;
        }

        @Bean
        McpToolCatalogService toolCatalogService(ISessionRepository sessionRepository,
                CelEvaluationService celEvaluationService) {
            McpToolCatalogService service = new McpToolCatalogService();
            ReflectionTestUtils.setField(service, "repository", sessionRepository);
            ReflectionTestUtils.setField(service, "celEvaluationService", celEvaluationService);
            return service;
        }

        @Bean
        McpToolInvocationService toolInvocationService(ISessionRepository sessionRepository,
                ISessionPort sessionPort, CelEvaluationService celEvaluationService) {
            McpToolInvocationService service = new McpToolInvocationService();
            ReflectionTestUtils.setField(service, "repository", sessionRepository);
            ReflectionTestUtils.setField(service, "port", sessionPort);
            ReflectionTestUtils.setField(service, "celEvaluationService", celEvaluationService);
            return service;
        }

        @Bean
        ObservabilityClient observabilityClient() {
            return OBSERVABILITY_CLIENT_MOCK;
        }

        /** ObservabilityClient 上的 @Autowired config 字段在切片上下文也需要候选（mock 不读它） */
        @Bean
        cn.chyuan.ai.observability.client.ObservabilityClientConfig observabilityClientConfig() {
            return new cn.chyuan.ai.observability.client.ObservabilityClientConfig();
        }

        @Bean
        ObservabilityHelper observabilityHelper(ObservabilityClient observabilityClient) {
            ObservabilityHelper helper = new ObservabilityHelper();
            ReflectionTestUtils.setField(helper, "observabilityClient", observabilityClient);
            return helper;
        }

        @Bean
        GatewayMcpServerRegistry gatewayMcpServerRegistry(ISessionRepository sessionRepository,
                IMcpToolCatalogService toolCatalogService, IMcpToolInvocationService toolInvocationService,
                ObservabilityHelper observabilityHelper) {
            GatewayMcpServerRegistry registry = new GatewayMcpServerRegistry();
            ReflectionTestUtils.setField(registry, "sessionRepository", sessionRepository);
            ReflectionTestUtils.setField(registry, "toolCatalogService", toolCatalogService);
            ReflectionTestUtils.setField(registry, "toolInvocationService", toolInvocationService);
            ReflectionTestUtils.setField(registry, "observabilityHelper", observabilityHelper);
            ReflectionTestUtils.setField(registry, "requestTimeoutMs", 60000L);
            ReflectionTestUtils.setField(registry, "toolSpecRefreshSeconds", 300L);
            return registry;
        }

        @Bean
        McpGatewayDelegateServlet mcpGatewayDelegateServlet(GatewayMcpServerRegistry registry,
                IMcpToolCatalogService toolCatalogService, ObservabilityHelper observabilityHelper) {
            return new McpGatewayDelegateServlet(registry, toolCatalogService, null,
                    observabilityHelper, 30L);
        }

        @Bean
        ServletRegistrationBean<McpGatewayDelegateServlet> mcpGatewayDelegateServletRegistration(
                McpGatewayDelegateServlet servlet) {
            ServletRegistrationBean<McpGatewayDelegateServlet> registration =
                    new ServletRegistrationBean<>(servlet, "/api-gateway/*");
            registration.setAsyncSupported(true);
            registration.setName("mcpGatewayDelegateServlet");
            return registration;
        }

        @Bean
        FilterRegistrationBean<GovernanceAuthFilter> governanceAuthFilter(
                IGovernanceAuthService governanceAuthService) {
            FilterRegistrationBean<GovernanceAuthFilter> registration = new FilterRegistrationBean<>();
            registration.setFilter(new GovernanceAuthFilter(governanceAuthService));
            registration.addUrlPatterns("/api-gateway/*");
            registration.setOrder(10);
            registration.setName("governanceAuthFilter");
            return registration;
        }

        @Bean
        FilterRegistrationBean<QuotaEnforcementFilter> quotaEnforcementFilter(IQuotaService quotaService) {
            FilterRegistrationBean<QuotaEnforcementFilter> registration = new FilterRegistrationBean<>();
            registration.setFilter(new QuotaEnforcementFilter(quotaService));
            registration.addUrlPatterns("/api-gateway/*");
            registration.setOrder(11);
            registration.setName("quotaEnforcementFilter");
            return registration;
        }

        private static String extractToolName(String url) {
            // 假端口用 url 尾段携带工具名：http://fake/business_order_query
            int slash = url.lastIndexOf('/');
            return url.substring(slash + 1);
        }
    }

    /** 网关配置 + 三来源工具 + CEL 拒绝工具的内存仓储 */
    static class FakeSessionRepository implements ISessionRepository {

        private final Map<String, List<McpToolConfigVO>> toolsByGateway = new ConcurrentHashMap<>();

        FakeSessionRepository() {
            toolsByGateway.put(GATEWAY_ID, List.of(
                    tool("business_order_query", "业务系统订单查询（协议映射 → agent-add-oil）"),
                    tool("local_report_generator", "本地报表生成（协议映射 → 本地服务）"),
                    tool("external_weather_lookup", "外部天气查询（协议映射 → 外部系统）"),
                    tool("secret_tool", "治理规则不放行的工具")));
        }

        @Override
        public McpGatewayConfigVO queryMcpGatewayConfigByGatewayId(String gatewayId) {
            if (!GATEWAY_ID.equals(gatewayId)) {
                return null;
            }
            return McpGatewayConfigVO.builder()
                    .gatewayId(GATEWAY_ID)
                    .gatewayName("gateway-business-test")
                    .gatewayDesc("streamable HTTP 协议级测试网关")
                    .version("9.9.9")
                    .build();
        }

        @Override
        public List<McpToolConfigVO> queryMcpGatewayToolConfigListByGatewayId(String gatewayId) {
            return toolsByGateway.getOrDefault(gatewayId, List.of());
        }

        @Override
        public McpToolProtocolConfigVO queryMcpGatewayProtocolConfig(String gatewayId, String toolName) {
            boolean exists = queryMcpGatewayToolConfigListByGatewayId(gatewayId).stream()
                    .anyMatch(tool -> tool.getToolName().equals(toolName));
            if (!exists) {
                return null;
            }
            McpToolProtocolConfigVO.HTTPConfig httpConfig = new McpToolProtocolConfigVO.HTTPConfig();
            httpConfig.setHttpUrl("http://127.0.0.1:1/fake/" + toolName);
            httpConfig.setHttpMethod("POST");
            McpToolProtocolConfigVO.ProtocolMapping mapping = McpToolProtocolConfigVO.ProtocolMapping.builder()
                    .mappingType("request")
                    .parentPath(null)
                    .fieldName("orderId")
                    .mcpPath("orderId")
                    .mcpType("string")
                    .mcpDesc("订单ID")
                    .isRequired(1)
                    .sortOrder(1)
                    .build();
            return McpToolProtocolConfigVO.builder()
                    .httpConfig(httpConfig)
                    .requestProtocolMappings(List.of(mapping))
                    .build();
        }

        private static McpToolConfigVO tool(String toolName, String description) {
            return McpToolConfigVO.builder()
                    .gatewayId(GATEWAY_ID)
                    .toolName(toolName)
                    .toolDescription(description)
                    .mcpToolProtocolConfigVO(McpToolProtocolConfigVO.builder()
                            .requestProtocolMappings(List.of(McpToolProtocolConfigVO.ProtocolMapping.builder()
                                    .mappingType("request")
                                    .parentPath(null)
                                    .fieldName("orderId")
                                    .mcpPath("orderId")
                                    .mcpType("string")
                                    .mcpDesc("订单ID")
                                    .isRequired(1)
                                    .sortOrder(1)
                                    .build()))
                            .build())
                    .build();
        }
    }

    /** 认证假实现：vk-test-key 放行、其余 403、无凭证 401（口径对齐 0017） */
    static class FakeGovernanceAuthService implements IGovernanceAuthService {

        @Override
        public GovernancePrincipal authenticate(String gatewayId, String credential) {
            if (credential == null || credential.isBlank()) {
                throw new AppException(McpErrorCodes.AUTH_REQUIRED, "缺少凭证");
            }
            String token = credential.startsWith("Bearer ") ? credential.substring(7) : credential;
            if (VALID_KEY.equals(token)) {
                return GovernancePrincipal.builder()
                        .authType(GovernancePrincipal.AuthType.VIRTUAL_KEY)
                        .virtualKeyId(42L)
                        .apiKeyHash("hash-of-" + VALID_KEY)
                        .rpmLimit(60)
                        .dailyRequestLimit(1000)
                        .build();
            }
            throw new AppException(McpErrorCodes.INSUFFICIENT_PERMISSIONS, "凭证无效或未授权该网关");
        }

        @Override
        public GovernancePrincipal validatePrincipal(String gatewayId, GovernancePrincipal principal) {
            return principal;
        }
    }

    /** 配额假实现：默认放行，可切换拒绝（429 断言用） */
    static class FakeQuotaService implements IQuotaService {

        static volatile boolean deny = false;

        @Override
        public QuotaVerdict checkAndConsume(String gatewayId, GovernancePrincipal principal) {
            if (principal == null || principal.getAuthType() != GovernancePrincipal.AuthType.VIRTUAL_KEY) {
                return new QuotaVerdict(true, false, -1L, 0L);
            }
            if (deny) {
                return new QuotaVerdict(false, true, 0L, 7L);
            }
            return new QuotaVerdict(true, true, 99L, 0L);
        }
    }

    /** CEL 规则假仓储：GATEWAY 作用域拒绝 secret_tool（其余放行交给无规则默认） */
    static class FakeCelRuleRepository implements ICelRuleRepository {

        private final List<CelRuleVO> rules = new CopyOnWriteArrayList<>(List.of(rule()));

        static CelRuleVO rule() {
            CelRuleVO vo = new CelRuleVO();
            vo.setId(1L);
            vo.setRuleName("deny-secret-tool");
            vo.setExpression("mcp.tool.name != 'secret_tool'");
            vo.setScopeType("GATEWAY");
            vo.setGatewayId(GATEWAY_ID);
            vo.setStatus("ACTIVE");
            vo.setCreatedAt(new Date());
            return vo;
        }

        @Override
        public void insert(CelRuleVO rule) {
            rules.add(rule);
        }

        @Override
        public boolean update(CelRuleVO rule) {
            return true;
        }

        @Override
        public boolean deleteById(Long id) {
            return true;
        }

        @Override
        public List<CelRuleVO> findAllActive() {
            return new ArrayList<>(rules);
        }

        @Override
        public CelRuleVO findById(Long id) {
            return rules.get(0);
        }

        @Override
        public List<CelRuleVO> findByPage(String keyword, int offset, int size) {
            return findAllActive();
        }

        @Override
        public long count(String keyword) {
            return rules.size();
        }
    }
}

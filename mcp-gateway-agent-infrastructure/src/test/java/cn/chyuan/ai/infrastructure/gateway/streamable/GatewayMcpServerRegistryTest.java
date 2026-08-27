package cn.chyuan.ai.infrastructure.gateway.streamable;

import cn.chyuan.ai.domain.session.adapter.repository.ISessionRepository;
import cn.chyuan.ai.domain.session.model.valobj.McpSchemaVO;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpGatewayConfigVO;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolConfigVO;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolProtocolConfigVO;
import cn.chyuan.ai.domain.session.service.tool.IMcpToolCatalogService;
import cn.chyuan.ai.domain.session.service.tool.IMcpToolInvocationService;
import cn.chyuan.ai.infrastructure.utils.ObservabilityHelper;
import cn.chyuan.ai.types.exception.AppException;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
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
 * 网关官方服务器注册表测试（工单 0020：构建/TTL 刷新/失效；协议级行为由 StreamableHttpProtocolTest 覆盖）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GatewayMcpServerRegistry 测试")
class GatewayMcpServerRegistryTest {

    private static final String GATEWAY_ID = "gateway_business";

    @Mock
    private ISessionRepository sessionRepository;

    @Mock
    private IMcpToolCatalogService toolCatalogService;

    @Mock
    private IMcpToolInvocationService toolInvocationService;

    @Mock
    private ObservabilityHelper observabilityHelper;

    private GatewayMcpServerRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new GatewayMcpServerRegistry();
        ReflectionTestUtils.setField(registry, "sessionRepository", sessionRepository);
        ReflectionTestUtils.setField(registry, "toolCatalogService", toolCatalogService);
        ReflectionTestUtils.setField(registry, "toolInvocationService", toolInvocationService);
        ReflectionTestUtils.setField(registry, "observabilityHelper", observabilityHelper);
        ReflectionTestUtils.setField(registry, "requestTimeoutMs", 60000L);
        ReflectionTestUtils.setField(registry, "toolSpecRefreshSeconds", 300L);

        lenient().when(sessionRepository.queryMcpGatewayConfigByGatewayId(GATEWAY_ID))
                .thenReturn(McpGatewayConfigVO.builder()
                        .gatewayId(GATEWAY_ID)
                        .gatewayName("gw")
                        .gatewayDesc("desc")
                        .version("1.2.3")
                        .build());
        lenient().when(toolCatalogService.visibleTools(eq(GATEWAY_ID), any(), anyString()))
                .thenReturn(List.of(
                        new McpSchemaVO.Tool("tool_a", "A", schema()),
                        new McpSchemaVO.Tool("tool_b", "B", schema())));
    }

    private McpSchemaVO.JsonSchema schema() {
        return new McpSchemaVO.JsonSchema("object", Map.of("orderId", Map.of("type", "string")),
                List.of("orderId"), false, null, null);
    }

    @Test
    @DisplayName("惰性构建：per-gateway 官方服务器带全部工具规格与 serverInfo")
    void buildsServerWithToolSpecs() {
        var transport = registry.transportOf(GATEWAY_ID);

        assertThat(transport).isNotNull();
        // 复用 registry 内部条目校验官方服务器内容
        Map<String, GatewayMcpServerRegistry.GatewayServerEntry> servers = serverMap();
        McpSyncServer server = servers.get(GATEWAY_ID).server();
        assertThat(server.listTools()).extracting(McpSchema.Tool::name)
                .containsExactlyInAnyOrder("tool_a", "tool_b");
        assertThat(server.getServerInfo().name()).isEqualTo("gw");
        assertThat(server.getServerInfo().version()).isEqualTo("1.2.3");
    }

    @Test
    @DisplayName("同一网关复用同一实例（不重复构建）")
    void reusesEntryPerGateway() {
        var first = registry.transportOf(GATEWAY_ID);
        var second = registry.transportOf(GATEWAY_ID);
        assertThat(first).isSameAs(second);
    }

    @Test
    @DisplayName("未知网关 — 抛 NOT_FOUND（委派层转 404）")
    void unknownGatewayThrowsNotFound() {
        assertThatThrownBy(() -> registry.transportOf("gateway_missing"))
                .isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("TTL 过期后活体刷新：diff 增删并保留实例")
    void refreshesToolSpecsAfterTtl() {
        registry.transportOf(GATEWAY_ID);
        Map<String, GatewayMcpServerRegistry.GatewayServerEntry> servers = serverMap();
        GatewayMcpServerRegistry.GatewayServerEntry entry = servers.get(GATEWAY_ID);

        // 目录变化：tool_a 移除、tool_c 新增
        when(toolCatalogService.visibleTools(eq(GATEWAY_ID), any(), anyString()))
                .thenReturn(List.of(
                        new McpSchemaVO.Tool("tool_b", "B", schema()),
                        new McpSchemaVO.Tool("tool_c", "C", schema())));
        // 时间戳拨回过期
        ReflectionTestUtils.setField(entry, "refreshedAt", System.currentTimeMillis() - 301_000);

        registry.transportOf(GATEWAY_ID);

        assertThat(entry.server().listTools()).extracting(McpSchema.Tool::name)
                .containsExactlyInAnyOrder("tool_b", "tool_c");
        // 同一实例（未重建、会话保留）
        assertThat(servers.get(GATEWAY_ID)).isSameAs(entry);
    }

    @Test
    @DisplayName("evict — 条目移除并可再次构建")
    void evictRemovesEntry() {
        registry.transportOf(GATEWAY_ID);
        registry.evict(GATEWAY_ID);
        assertThat(serverMap()).doesNotContainKey(GATEWAY_ID);

        var rebuilt = registry.transportOf(GATEWAY_ID);
        assertThat(rebuilt).isNotNull();
    }

    @SuppressWarnings("unchecked")
    private Map<String, GatewayMcpServerRegistry.GatewayServerEntry> serverMap() {
        return (Map<String, GatewayMcpServerRegistry.GatewayServerEntry>)
                ReflectionTestUtils.getField(registry, "servers");
    }
}

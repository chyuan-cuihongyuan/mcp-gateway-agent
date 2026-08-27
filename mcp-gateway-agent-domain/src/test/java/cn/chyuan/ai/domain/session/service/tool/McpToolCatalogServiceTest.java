package cn.chyuan.ai.domain.session.service.tool;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import cn.chyuan.ai.domain.governance.service.ICelEvaluationService;
import cn.chyuan.ai.domain.session.adapter.repository.ISessionRepository;
import cn.chyuan.ai.domain.session.model.valobj.McpSchemaVO;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolConfigVO;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolProtocolConfigVO;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具目录服务测试（工单 0020：承接 0018 的 tools/list 生效点语义与 schema 构建）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MCP 工具目录服务测试")
class McpToolCatalogServiceTest {

    @Mock
    private ISessionRepository repository;

    @Mock
    private ICelEvaluationService celEvaluationService;

    private McpToolCatalogService service;

    @BeforeEach
    void setUp() {
        service = new McpToolCatalogService();
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "celEvaluationService", celEvaluationService);
    }

    @Test
    @DisplayName("CEL 治理过滤（0018 生效点承接）— 不放行的工具从清单隐藏")
    void celGovernanceHidesDeniedTools() {
        when(repository.queryMcpGatewayToolConfigListByGatewayId("gateway_business"))
                .thenReturn(List.of(toolConfig("agent_order_query"), toolConfig("secret_tool")));
        GovernancePrincipal principal = GovernancePrincipal.builder()
                .authType(GovernancePrincipal.AuthType.VIRTUAL_KEY).virtualKeyId(42L).build();
        lenient().when(celEvaluationService.isToolAllowed(eq(principal), eq("gateway_business"),
                        eq("tools/list"), anyString(), eq("PROTOCOL")))
                .thenAnswer(inv -> !"secret_tool".equals(inv.getArgument(3)));

        List<McpSchemaVO.Tool> tools = service.visibleTools("gateway_business", principal, "tools/list");

        assertThat(tools).extracting(McpSchemaVO.Tool::name).containsExactly("agent_order_query");
    }

    @Test
    @DisplayName("无认证主体（遗留路径）— 不过滤全量返回")
    void legacyPathWithoutPrincipalSkipsFiltering() {
        when(repository.queryMcpGatewayToolConfigListByGatewayId("gateway_business"))
                .thenReturn(List.of(toolConfig("agent_order_query")));

        List<McpSchemaVO.Tool> tools = service.visibleTools("gateway_business", null, "tools/list");

        assertThat(tools).extracting(McpSchemaVO.Tool::name).containsExactly("agent_order_query");
        verify(celEvaluationService, org.mockito.Mockito.never())
                .isToolAllowed(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("schema 构建 — 协议映射转为 object 输入结构（含 required）")
    void buildsJsonSchemaFromProtocolMappings() {
        when(repository.queryMcpGatewayToolConfigListByGatewayId("gateway_business"))
                .thenReturn(List.of(toolConfig("agent_order_query")));

        List<McpSchemaVO.Tool> tools = service.visibleTools("gateway_business", null, "tools/list");

        McpSchemaVO.Tool tool = tools.get(0);
        assertThat(tool.name()).isEqualTo("agent_order_query");
        assertThat(tool.description()).isEqualTo("工具-agent_order_query");
        assertThat(tool.inputSchema().type()).isEqualTo("object");
        assertThat(tool.inputSchema().properties()).containsKey("orderId");
        assertThat(tool.inputSchema().required()).containsExactly("orderId");
    }

    @Test
    @DisplayName("遗留 request 包装映射 — 提升为顶层工具参数")
    void legacyRequestWrapperMappingsAreExposedAsTopLevelArguments() {
        McpToolProtocolConfigVO.ProtocolMapping orderIdMapping = McpToolProtocolConfigVO.ProtocolMapping.builder()
                .mappingType("request")
                .parentPath("request")
                .fieldName("orderId")
                .mcpPath("request.orderId")
                .mcpType("string")
                .mcpDesc("订单ID")
                .isRequired(1)
                .sortOrder(1)
                .build();
        when(repository.queryMcpGatewayToolConfigListByGatewayId("gateway_business"))
                .thenReturn(List.of(McpToolConfigVO.builder()
                        .toolName("agent_order_query")
                        .toolDescription("查询订单")
                        .mcpToolProtocolConfigVO(McpToolProtocolConfigVO.builder()
                                .requestProtocolMappings(List.of(orderIdMapping))
                                .build())
                        .build()));

        List<McpSchemaVO.Tool> tools = service.visibleTools("gateway_business", null, "tools/list");

        McpSchemaVO.Tool tool = tools.get(0);
        assertThat(tool.inputSchema().type()).isEqualTo("object");
        assertThat(tool.inputSchema().properties()).containsKey("orderId");
    }

    @Test
    @DisplayName("toolExists — 按配置判断已知/未知工具")
    void toolExistsReflectsCatalog() {
        when(repository.queryMcpGatewayToolConfigListByGatewayId("gateway_business"))
                .thenReturn(List.of(toolConfig("agent_order_query")));

        assertThat(service.toolExists("gateway_business", "agent_order_query")).isTrue();
        assertThat(service.toolExists("gateway_business", "ghost_tool")).isFalse();
        assertThat(service.toolExists("gateway_business", null)).isFalse();
    }

    @Test
    @DisplayName("嵌套映射 — 父子结构转为嵌套 properties")
    void nestedMappingsBuildNestedProperties() {
        McpToolProtocolConfigVO.ProtocolMapping parent = McpToolProtocolConfigVO.ProtocolMapping.builder()
                .mappingType("request")
                .parentPath(null)
                .fieldName("query")
                .mcpPath("query")
                .mcpType("object")
                .mcpDesc("查询条件")
                .isRequired(1)
                .sortOrder(1)
                .build();
        McpToolProtocolConfigVO.ProtocolMapping child = McpToolProtocolConfigVO.ProtocolMapping.builder()
                .mappingType("request")
                .parentPath("query")
                .fieldName("city")
                .mcpPath("query.city")
                .mcpType("string")
                .mcpDesc("城市")
                .isRequired(1)
                .sortOrder(1)
                .build();
        when(repository.queryMcpGatewayToolConfigListByGatewayId("gateway_business"))
                .thenReturn(List.of(McpToolConfigVO.builder()
                        .toolName("nested_query")
                        .toolDescription("嵌套查询")
                        .mcpToolProtocolConfigVO(McpToolProtocolConfigVO.builder()
                                .requestProtocolMappings(List.of(parent, child))
                                .build())
                        .build()));

        List<McpSchemaVO.Tool> tools = service.visibleTools("gateway_business", null, "tools/list");

        Map<String, Object> queryProperty = tools.get(0).inputSchema().properties();
        assertThat(queryProperty).containsKey("query");
        @SuppressWarnings("unchecked")
        Map<String, Object> queryProps = (Map<String, Object>) queryProperty.get("query");
        assertThat(queryProps).containsKey("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> city = ((Map<String, Object>) queryProps.get("properties"));
        assertThat(city).containsKey("city");
    }

    private McpToolConfigVO toolConfig(String toolName) {
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
        return McpToolConfigVO.builder()
                .toolName(toolName)
                .toolDescription("工具-" + toolName)
                .mcpToolProtocolConfigVO(McpToolProtocolConfigVO.builder()
                        .requestProtocolMappings(List.of(mapping))
                        .build())
                .build();
    }
}

package cn.chyuan.ai.domain.session.service.message.handler.impl;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import cn.chyuan.ai.domain.governance.service.ICelEvaluationService;
import cn.chyuan.ai.domain.session.adapter.repository.ISessionRepository;
import cn.chyuan.ai.domain.session.model.valobj.McpSchemaVO;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolConfigVO;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolProtocolConfigVO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolsListHandlerTest {

    @SuppressWarnings("unchecked")
    private List<String> toolNamesOf(McpSchemaVO.JSONRPCResponse response) {
        Map<?, ?> result = (Map<?, ?>) response.result();
        List<?> tools = (List<?>) result.get("tools");
        return tools.stream()
                .map(t -> ((McpSchemaVO.Tool) t).name())
                .toList();
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("CEL 治理过滤（工单 0018）— 不放行的工具从 tools/list 隐藏")
    void celGovernanceHidesDeniedTools() {
        ISessionRepository repository = mock(ISessionRepository.class);
        ICelEvaluationService celEvaluationService = mock(ICelEvaluationService.class);
        ToolsListHandler handler = new ToolsListHandler();
        ReflectionTestUtils.setField(handler, "repository", repository);
        ReflectionTestUtils.setField(handler, "celEvaluationService", celEvaluationService);

        McpToolConfigVO allowed = toolConfig("agent_order_query");
        McpToolConfigVO denied = toolConfig("secret_tool");
        when(repository.queryMcpGatewayToolConfigListByGatewayId("gateway_business"))
                .thenReturn(List.of(allowed, denied));
        GovernancePrincipal principal = GovernancePrincipal.builder()
                .authType(GovernancePrincipal.AuthType.VIRTUAL_KEY).virtualKeyId(42L).build();
        lenient().when(celEvaluationService.isToolAllowed(eq(principal), eq("gateway_business"),
                        eq("tools/list"), anyString(), eq("PROTOCOL")))
                .thenAnswer(inv -> !"secret_tool".equals(inv.getArgument(3)));

        McpSchemaVO.JSONRPCResponse response = handler.handle("gateway_business",
                new McpSchemaVO.JSONRPCRequest("2.0", "tools/list", 1, Map.of()), principal);

        assertThat(toolNamesOf(response)).containsExactly("agent_order_query");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("无认证主体（遗留路径）— CEL 服务按放行处理，不过滤")
    void legacyPathWithoutPrincipalSkipsFiltering() {
        ISessionRepository repository = mock(ISessionRepository.class);
        ICelEvaluationService celEvaluationService = mock(ICelEvaluationService.class);
        ToolsListHandler handler = new ToolsListHandler();
        ReflectionTestUtils.setField(handler, "repository", repository);
        ReflectionTestUtils.setField(handler, "celEvaluationService", celEvaluationService);

        when(repository.queryMcpGatewayToolConfigListByGatewayId("gateway_business"))
                .thenReturn(List.of(toolConfig("agent_order_query")));

        McpSchemaVO.JSONRPCResponse response = handler.handle("gateway_business",
                new McpSchemaVO.JSONRPCRequest("2.0", "tools/list", 1, Map.of()), null);

        assertThat(toolNamesOf(response)).containsExactly("agent_order_query");
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

    @Test
    void legacyRequestWrapperMappingsAreExposedAsTopLevelToolArguments() {
        ISessionRepository repository = mock(ISessionRepository.class);
        ToolsListHandler handler = new ToolsListHandler();
        ReflectionTestUtils.setField(handler, "repository", repository);

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
        McpToolConfigVO toolConfig = McpToolConfigVO.builder()
                .toolName("agent_order_query")
                .toolDescription("查询订单")
                .mcpToolProtocolConfigVO(McpToolProtocolConfigVO.builder()
                        .requestProtocolMappings(List.of(orderIdMapping))
                        .build())
                .build();
        when(repository.queryMcpGatewayToolConfigListByGatewayId("gateway_business"))
                .thenReturn(List.of(toolConfig));

        McpSchemaVO.JSONRPCResponse response = handler.handle("gateway_business",
                new McpSchemaVO.JSONRPCRequest("2.0", "tools/list", 1, Map.of()));

        Map<?, ?> result = (Map<?, ?>) response.result();
        List<?> tools = (List<?>) result.get("tools");
        McpSchemaVO.Tool tool = (McpSchemaVO.Tool) tools.get(0);

        assertThat(tool.inputSchema().type()).isEqualTo("object");
        assertThat(tool.inputSchema().properties()).containsKey("orderId");
        assertThat(tool.inputSchema().properties()).doesNotContainKey("request");
        assertThat(tool.inputSchema().required()).containsExactly("orderId");
    }

    @Test
    void duplicateMappingsAreDeduplicatedInRequiredSchema() {
        ISessionRepository repository = mock(ISessionRepository.class);
        ToolsListHandler handler = new ToolsListHandler();
        ReflectionTestUtils.setField(handler, "repository", repository);

        McpToolProtocolConfigVO.ProtocolMapping first = McpToolProtocolConfigVO.ProtocolMapping.builder()
                .mappingType("request")
                .parentPath(null)
                .fieldName("orderId")
                .mcpPath("orderId")
                .mcpType("string")
                .mcpDesc("订单ID")
                .isRequired(1)
                .sortOrder(1)
                .build();
        McpToolProtocolConfigVO.ProtocolMapping duplicate = McpToolProtocolConfigVO.ProtocolMapping.builder()
                .mappingType("request")
                .parentPath(null)
                .fieldName("orderId")
                .mcpPath("orderId")
                .mcpType("string")
                .mcpDesc("订单ID")
                .isRequired(1)
                .sortOrder(2)
                .build();
        McpToolConfigVO toolConfig = McpToolConfigVO.builder()
                .toolName("agent_order_query")
                .toolDescription("查询订单")
                .mcpToolProtocolConfigVO(McpToolProtocolConfigVO.builder()
                        .requestProtocolMappings(List.of(first, duplicate))
                        .build())
                .build();
        when(repository.queryMcpGatewayToolConfigListByGatewayId("gateway_business"))
                .thenReturn(List.of(toolConfig));

        McpSchemaVO.JSONRPCResponse response = handler.handle("gateway_business",
                new McpSchemaVO.JSONRPCRequest("2.0", "tools/list", 1, Map.of()));

        Map<?, ?> result = (Map<?, ?>) response.result();
        List<?> tools = (List<?>) result.get("tools");
        McpSchemaVO.Tool tool = (McpSchemaVO.Tool) tools.get(0);

        assertThat(tool.inputSchema().properties()).containsOnlyKeys("orderId");
        assertThat(tool.inputSchema().required()).containsExactly("orderId");
    }

    @Test
    void blankParentPathIsExposedAsTopLevelToolArgument() {
        ISessionRepository repository = mock(ISessionRepository.class);
        ToolsListHandler handler = new ToolsListHandler();
        ReflectionTestUtils.setField(handler, "repository", repository);

        McpToolProtocolConfigVO.ProtocolMapping sessionIdMapping = McpToolProtocolConfigVO.ProtocolMapping.builder()
                .mappingType("request")
                .parentPath("")
                .fieldName("sessionId")
                .mcpPath("sessionId")
                .mcpType("string")
                .mcpDesc("会话ID")
                .isRequired(1)
                .sortOrder(1)
                .build();
        McpToolConfigVO toolConfig = McpToolConfigVO.builder()
                .toolName("query_chat_history")
                .toolDescription("查询历史")
                .mcpToolProtocolConfigVO(McpToolProtocolConfigVO.builder()
                        .requestProtocolMappings(List.of(sessionIdMapping))
                        .build())
                .build();
        when(repository.queryMcpGatewayToolConfigListByGatewayId("gateway_business"))
                .thenReturn(List.of(toolConfig));

        McpSchemaVO.JSONRPCResponse response = handler.handle("gateway_business",
                new McpSchemaVO.JSONRPCRequest("2.0", "tools/list", 1, Map.of()));

        Map<?, ?> result = (Map<?, ?>) response.result();
        List<?> tools = (List<?>) result.get("tools");
        McpSchemaVO.Tool tool = (McpSchemaVO.Tool) tools.get(0);

        assertThat(tool.inputSchema().properties()).containsKey("sessionId");
        assertThat(tool.inputSchema().required()).containsExactly("sessionId");
    }
}

package cn.chyuan.ai.domain.session.service.message.handler.impl;

import cn.chyuan.ai.domain.session.adapter.repository.ISessionRepository;
import cn.chyuan.ai.domain.session.model.valobj.McpSchemaVO;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolConfigVO;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolProtocolConfigVO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolsListHandlerTest {

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

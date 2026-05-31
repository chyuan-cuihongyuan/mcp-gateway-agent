package cn.chyuan.ai.domain.agent.service.armory.matter.mcp.server;

import cn.chyuan.ai.domain.session.adapter.port.ISessionPort;
import cn.chyuan.ai.domain.session.adapter.repository.ISessionRepository;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolProtocolConfigVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class YunfanOilBusinessToolsTest {

    @Test
    void queryOrderPassesTopLevelOrderIdToHttpTool() throws Exception {
        ISessionRepository repository = mock(ISessionRepository.class);
        ISessionPort sessionPort = mock(ISessionPort.class);
        McpToolProtocolConfigVO.HTTPConfig httpConfig = new McpToolProtocolConfigVO.HTTPConfig();

        McpToolProtocolConfigVO protocolConfig = McpToolProtocolConfigVO.builder()
                .httpConfig(httpConfig)
                .build();
        when(repository.queryMcpGatewayProtocolConfig("gateway_business", "agent_order_query"))
                .thenReturn(protocolConfig);
        when(sessionPort.toolCall(eq(httpConfig), any())).thenReturn("{\"code\":0}");

        YunfanOilBusinessTools tools = new YunfanOilBusinessTools();
        ReflectionTestUtils.setField(tools, "repository", repository);
        ReflectionTestUtils.setField(tools, "sessionPort", sessionPort);

        String result = tools.queryOrder("OD012026052515030031863");

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(sessionPort).toolCall(eq(httpConfig), paramsCaptor.capture());
        assertThat(result).isEqualTo("{\"code\":0}");
        assertThat(paramsCaptor.getValue()).containsEntry("orderId", "OD012026052515030031863");
        assertThat(paramsCaptor.getValue()).doesNotContainKey("request");
    }
}

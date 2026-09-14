package cn.chyuan.ai.domain.session.service.message.handler.impl;

import cn.chyuan.ai.domain.session.adapter.repository.ISessionRepository;
import cn.chyuan.ai.domain.session.model.valobj.McpSchemaVO;
import cn.chyuan.ai.domain.session.model.valobj.enums.SessionMessageHandlerMethodEnum;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpGatewayConfigVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * b-54 能力声明诚实性契约：capabilities 里承诺的每一项，方法注册面必须
 * 有对应 handler；listChanged 是"会发变更通知"的承诺——网关 DB 驱动、
 * 无变更推送链路，必须 false（声明的能力 = 实际可兑现的能力）。
 */
@DisplayName("Capabilities 与方法注册面一致性（b-54）")
class CapabilityHonestyTest {

    @Test
    void declaredListChangedFlagsAreHonest() {
        McpSchemaVO.InitializeResult result = initialize();

        // 网关没有 tools/resources 变更推送机制（DB 配置变更无通知链路）
        assertThat(result.capabilities().tools().listChanged()).as("tools.listChanged").isFalse();
        assertThat(result.capabilities().resources().listChanged()).as("resources.listChanged").isFalse();
        assertThat(result.capabilities().resources().subscribe()).as("resources.subscribe").isFalse();
    }

    @Test
    void everyDeclaredCapabilityHasRegisteredMethod() {
        McpSchemaVO.InitializeResult result = initialize();
        List<String> methods = Arrays.stream(SessionMessageHandlerMethodEnum.values())
                .map(SessionMessageHandlerMethodEnum::getMethod)
                .toList();

        if (result.capabilities().tools() != null) {
            assertThat(methods).contains("tools/list", "tools/call");
        }
        if (result.capabilities().resources() != null) {
            assertThat(methods).contains("resources/list");
        }
        if (result.capabilities().prompts() != null) {
            // prompts 有能力声明但注册面无 prompts/list handler——当前即为缺口，
            // 该断言失败即提示"要么实现要么关掉声明"
            assertThat(methods).contains("prompts/list");
        }
    }

    private McpSchemaVO.InitializeResult initialize() {
        ISessionRepository repository = mock(ISessionRepository.class);
        when(repository.queryMcpGatewayConfigByGatewayId("gateway_001"))
                .thenReturn(McpGatewayConfigVO.builder()
                        .gatewayId("gateway_001")
                        .gatewayName("gateway")
                        .gatewayDesc("desc")
                        .version("1.0.0")
                        .build());

        InitializeHandler handler = new InitializeHandler();
        ReflectionTestUtils.setField(handler, "repository", repository);
        ReflectionTestUtils.setField(handler, "supportedProtocolVersions", List.of("2024-11-05", "2025-03-26"));
        ReflectionTestUtils.setField(handler, "defaultProtocolVersion", "2024-11-05");

        McpSchemaVO.JSONRPCResponse response = handler.handle("gateway_001",
                new McpSchemaVO.JSONRPCRequest("2.0", "initialize", 1,
                        Map.of("protocolVersion", "2025-03-26")));
        return (McpSchemaVO.InitializeResult) response.result();
    }
}

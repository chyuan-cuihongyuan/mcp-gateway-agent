package cn.chyuan.ai.domain.session.service.message.handler.impl;

import cn.chyuan.ai.domain.session.adapter.repository.ISessionRepository;
import cn.chyuan.ai.domain.session.model.valobj.McpSchemaVO;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpGatewayConfigVO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InitializeHandlerTest {

    @Test
    void supportedClientProtocolVersionIsReturned() {
        InitializeHandler handler = initializeHandler(List.of("2024-11-05", "2025-03-26"), "2024-11-05");

        McpSchemaVO.JSONRPCResponse response = handler.handle("gateway_001", initializeRequest("2025-03-26"));

        McpSchemaVO.InitializeResult result = (McpSchemaVO.InitializeResult) response.result();
        assertThat(result.protocolVersion()).isEqualTo("2025-03-26");
    }

    @Test
    void unsupportedClientProtocolVersionFallsBackToDefault() {
        InitializeHandler handler = initializeHandler(List.of("2024-11-05"), "2024-11-05");

        McpSchemaVO.JSONRPCResponse response = handler.handle("gateway_001", initializeRequest("2020-01-01"));

        McpSchemaVO.InitializeResult result = (McpSchemaVO.InitializeResult) response.result();
        assertThat(result.protocolVersion()).isEqualTo("2024-11-05");
    }

    private InitializeHandler initializeHandler(List<String> supportedVersions, String defaultVersion) {
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
        ReflectionTestUtils.setField(handler, "supportedProtocolVersions", supportedVersions);
        ReflectionTestUtils.setField(handler, "defaultProtocolVersion", defaultVersion);
        return handler;
    }

    private McpSchemaVO.JSONRPCRequest initializeRequest(String protocolVersion) {
        return new McpSchemaVO.JSONRPCRequest("2.0", "initialize", 1,
                Map.of("protocolVersion", protocolVersion));
    }
}

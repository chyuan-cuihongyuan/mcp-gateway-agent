package cn.chyuan.ai.config;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {

    @Test
    void openApiMetadataDescribesGatewayWithoutHttpAuthScheme() {
        OpenAPI openApi = new OpenApiConfig().mcpGatewayOpenApi();

        assertThat(openApi.getInfo().getTitle()).isEqualTo("MCP Gateway Agent API");
        assertThat(openApi.getInfo().getVersion()).isEqualTo("1.0");
        assertThat(openApi.getInfo().getDescription()).contains("MCP");
        // 本服务无 HTTP 层统一鉴权（MCP 会话协议层 apiKey 建权）：文档不声明 security scheme
        assertThat(openApi.getComponents()).isNull();
    }
}

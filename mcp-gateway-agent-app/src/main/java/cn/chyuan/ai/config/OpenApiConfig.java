package cn.chyuan.ai.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc-openapi 文档装配（D06 第三仓）
 *
 * <p>Boot 4.1 适配线（springdoc 3.x）。分组按控制器职责：gateway（MCP 协议 SSE）、
 * agent（对话/编排 HTTP）、admin（运营配置后台）。</p>
 *
 * <p>本服务无 HTTP 层统一鉴权（MCP 会话在协议层以 apiKey 建权），
 * 文档如实不声明 security scheme。</p>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI mcpGatewayOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("MCP Gateway Agent API")
                        .description("MCP 协议网关与智能体调度：SSE 协议端点、Agent 对话编排、运营配置后台")
                        .version("1.0")
                        .contact(new Contact().name("chyuan").url("https://github.com/chyuan-cuihongyuan"))
                        .license(new License().name("Apache 2.0").url("https://www.apache.org/licenses/LICENSE-2.0")));
    }
}

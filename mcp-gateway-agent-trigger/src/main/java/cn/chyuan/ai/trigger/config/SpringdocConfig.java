package cn.chyuan.ai.trigger.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 治理 API OpenAPI 文档配置（工单 0080）
 *
 * <p>生产口径默认关闭：api-docs 与 swagger-ui 均迁入 /admin/v1 前缀
 * （api-docs 由 AdminJwtAuthFilter 的 JWT 门禁保护）；swagger-ui 资源
 * 默认禁用（springdoc.swagger-ui.enabled 未显式设置时强制 false），
 * dev 环境以 springdoc.swagger-ui.enabled=true 打开。
 *
 * @author chyuan
 */
@Configuration
public class SpringdocConfig {

    /** api-docs 输出路径（迁入 /admin/v1 → 自动获得 JWT 门禁） */
    public static final String API_DOCS_PATH = "/admin/v1/api-docs";

    @jakarta.annotation.Resource
    private Environment environment;

    @PostConstruct
    void applyDefaults() {
        // 生产默认关闭 UI：未显式配置时以系统属性兜底（dev 通过 yml 打开）
        if (!environment.containsProperty("springdoc.swagger-ui.enabled")) {
            System.setProperty("springdoc.swagger-ui.enabled", "false");
        }
        // api-docs 迁入 /admin/v1（吃 AdminJwtAuthFilter 门禁）+ 仅治理组
        if (!environment.containsProperty("springdoc.api-docs.path")) {
            System.setProperty("springdoc.api-docs.path", API_DOCS_PATH);
        }
        if (!environment.containsProperty("springdoc.paths-to-match")) {
            System.setProperty("springdoc.paths-to-match", "/admin/v1/**");
        }
    }

    @Bean
    public OpenAPI governanceOpenApi() {
        final String schemeName = "AdminJwt";
        return new OpenAPI()
                .info(new Info()
                        .title("MCP 网关治理 API")
                        .description("虚拟密钥 / CEL 规则与模板 / 渠道 / 用量账本 / 审计 / 配置快照 / "
                                + "webhook 等 /admin/v1 治理端点（登录之外的端点需 admin JWT）。")
                        .version("1.0"))
                .addSecurityItem(new SecurityRequirement().addList(schemeName))
                .components(new io.swagger.v3.oas.models.Components()
                        .addSecuritySchemes(schemeName,
                                new SecurityScheme()
                                        .name("Authorization")
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .description("治理台登录签发的 JWT（Bearer）")))
                .addServersItem(new Server().url("/"));
    }
}

package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.IAdminGovernanceService;
import cn.chyuan.ai.trigger.config.SpringdocConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 治理 API OpenAPI 文档结构测试（工单 0080）
 *
 * <p>验收：api-docs 输出覆盖全部 /admin/v1 端点（路径数断言）且不含非治理路径；
 * 路径已迁入 /admin/v1（生产由 AdminJwtAuthFilter 保护，此处只验文档结构）。
 */
@DisplayName("治理 API OpenAPI 文档测试")
@SpringBootTest(classes = SpringdocApiDocsTest.TestApp.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
class SpringdocApiDocsTest {

    @Configuration
    @EnableAutoConfiguration
    @Import({SpringdocConfig.class, AdminGovernanceController.class})
    static class TestApp {
        @Bean
        IAdminGovernanceService adminGovernanceService() {
            return Mockito.mock(IAdminGovernanceService.class);
        }
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    @DisplayName("api-docs 覆盖 /admin/v1 全端点且不含其他流量面路径")
    void apiDocsCoversGovernanceEndpoints() throws Exception {
        MvcResult result = mockMvc().perform(get(SpringdocConfig.API_DOCS_PATH))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = new ObjectMapper().readTree(result.getResponse().getContentAsString());
        JsonNode paths = body.path("paths");
        int pathCount = paths.size();
        // AdminGovernanceController 现有 20+ 端点：文档路径数下限断言
        org.junit.jupiter.api.Assertions.assertTrue(pathCount >= 20,
                "api-docs 路径数不足：" + pathCount);
        paths.fieldNames().forEachRemaining(path ->
                org.junit.jupiter.api.Assertions.assertTrue(path.startsWith("/admin/v1"),
                        "api-docs 泄漏非治理路径：" + path));
        // 安全方案（AdminJwt）已声明
        org.junit.jupiter.api.Assertions.assertTrue(body.path("components").path("securitySchemes").has("AdminJwt"));
    }
}

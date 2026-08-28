package cn.chyuan.ai.trigger.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.servlet.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * agent 宿主删除验证（工单 0022 / 0014 迁移完成门第 1 条）
 *
 * <p>AgentServiceController 删除后，/api/v1 四接口应随 Spring MVC 无映射自然 404。
 * 切片上下文沿用 StreamableHttpProtocolTest 的 TestApp 风格：仅起内嵌 Tomcat，
 * 不注册任何 /api/v1 映射——若未来有人重新引入 agent 对话控制器且映射生效，
 * 本用例将以非 404 失败，锁定「网关为纯 MCP 协议代理 + 治理平面」的边界。
 *
 * @author chyuan
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = AgentHostRemovalTest.TestApp.class)
class AgentHostRemovalTest {

    @LocalServerPort
    private int port;

    private HttpResponse<String> request(String method, String path, String body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + path))
                    .header("Content-Type", "application/json");
            if ("GET".equals(method)) {
                builder.GET();
            } else {
                builder.POST(HttpRequest.BodyPublishers.ofString(body));
            }
            return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("agent 宿主已删除 — /api/v1 四接口全部 404")
    void apiV1EndpointsReturn404() {
        assertThat(request("GET", "/api/v1/query_ai_agent_config_list", "").statusCode())
                .as("查询智能体配置列表接口应已下线").isEqualTo(404);
        assertThat(request("POST", "/api/v1/create_session",
                "{\"agentId\":\"1\",\"userId\":\"u\"}").statusCode())
                .as("创建会话接口应已下线").isEqualTo(404);
        assertThat(request("POST", "/api/v1/chat",
                "{\"agentId\":\"1\",\"userId\":\"u\",\"message\":\"hi\"}").statusCode())
                .as("同步对话接口应已下线").isEqualTo(404);
        assertThat(request("POST", "/api/v1/chat_stream",
                "{\"agentId\":\"1\",\"userId\":\"u\",\"message\":\"hi\"}").statusCode())
                .as("流式对话接口应已下线").isEqualTo(404);
    }

    @SpringBootConfiguration
    static class TestApp {

        /** 最小上下文缺占位符解析器，@Value 会拿到字面量——显式注册 */
        @Bean
        static PropertySourcesPlaceholderConfigurer placeholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        ServletWebServerFactory servletWebServerFactory() {
            // 显式随机端口：切片上下文无自动装配定制器，server.port=0 属性不会作用到
            // 手工声明的工厂上（默认 8080），会与 StreamableHttpProtocolTest 的缓存上下文抢端口
            TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
            factory.setPort(0);
            return factory;
        }
    }
}

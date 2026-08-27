package cn.chyuan.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 2 兼容配置
 *
 * <p>Spring Boot 4 默认自动配置 Jackson 3（tools.jackson），不再提供 Jackson 2 的
 * com.fasterxml.jackson.databind.ObjectMapper Bean。项目存量组件（如 MCP 消息节点的
 * JSON-RPC 序列化、ADK Spring AI 桥接）仍消费 Jackson 2 API，此处显式提供兜底 Bean。
 * Jackson 2/3 包名与坐标不同，可在同一 classpath 共存。
 *
 * @author chyuan
 */
@Configuration
public class Jackson2Config {

    @Bean
    public ObjectMapper jackson2ObjectMapper() {
        return new ObjectMapper();
    }

}

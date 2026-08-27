package cn.chyuan.ai.config;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 配置
 *
 * <p>Spring AI 2.0 起 spring-ai-openai 基于官方 openai-java SDK 重写，
 * 原 OpenAiApi Bean 由官方 OpenAIClient（OkHttp 实现）替代。
 *
 * @author chyuan
 *         2026/4/8 08:04
 */
@Configuration
public class LLMConfig {

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.chat.options.model}")
    private String model;

    @Bean
    public OpenAIClient openAIClient() {
        return OpenAIOkHttpClient.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();
    }

}

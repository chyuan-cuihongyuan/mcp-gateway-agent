package cn.chyuan.ai.infrastructure.adapter.port;

import cn.chyuan.ai.domain.llmchannel.adapter.port.ILlmHttpPort;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * LLM 上游出站端口——JDK HttpClient 实现（工单 0063）
 *
 * <p>lastResponseBody 经 ThreadLocal 承载（同一请求线程内 post 后即读）。
 *
 * @author chyuan
 */
@Component
public class JdkLlmHttpPort implements ILlmHttpPort {

    private final ThreadLocal<String> lastBody = new ThreadLocal<>();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public int postJson(String url, Map<String, String> headers, String body, int timeoutMs) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        lastBody.set(response.body());
        return response.statusCode();
    }

    @Override
    public String lastResponseBody() {
        return lastBody.get();
    }

    @Override
    public String getJson(String url, Map<String, String> headers, int timeoutMs) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .GET();
        headers.forEach(builder::header);
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    @Override
    public int postJsonStreaming(String url, Map<String, String> headers, String body, int timeoutMs,
            java.util.function.Consumer<String> onLine) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        HttpResponse<java.util.stream.Stream<String>> response = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofLines());
        try (java.util.stream.Stream<String> lines = response.body()) {
            lines.forEach(line -> onLine.accept(line + "\n"));
        }
        return response.statusCode();
    }
}

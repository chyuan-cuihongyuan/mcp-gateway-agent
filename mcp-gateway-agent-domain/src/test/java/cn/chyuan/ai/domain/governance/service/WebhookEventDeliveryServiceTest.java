package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.adapter.IWebhookHttpClient;
import cn.chyuan.ai.domain.governance.adapter.repository.IWebhookEndpointRepository;
import cn.chyuan.ai.domain.governance.model.valobj.WebhookEndpointVO;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 治理事件 webhook 投递测试（工单 0051：JDK HttpServer mock 接收端断言载荷/签名/订阅过滤/重试）
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("webhook 事件投递测试")
public class WebhookEventDeliveryServiceTest {

    /** 事件类型口径（与 BudgetService 发布一致） */
    private static final String BUDGET_EVENT = "BUDGET_SOFT_CROSSED";

    /** 简单轮询等待（替代 awaitility） */
    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(condition.getAsBoolean(), "等待条件超时");
    }

    @Mock
    private IWebhookEndpointRepository endpointRepository;

    @Mock
    private IWebhookHttpClient httpClient;

    @InjectMocks
    private WebhookEventDeliveryService service;

    /** mock 接收端捕获的请求（body/headers/path） */
    static final ConcurrentLinkedDeque<CapturedRequest> captured = new ConcurrentLinkedDeque<>();
    static final AtomicInteger failuresFirst = new AtomicInteger(0);

    record CapturedRequest(String body, Map<String, List<String>> headers) {
    }

    private HttpServer server;

    @BeforeEach
    public void setUp() throws Exception {
        captured.clear();
        failuresFirst.set(0);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "maxAttempts", 3);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "retryBackoffMs", 50L);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "adminBaseUrl", "http://admin.local");
        // 真实出站（inline JDK HttpClient，域测试不依赖 infrastructure 实现）
        org.springframework.test.util.ReflectionTestUtils.setField(service, "httpClient",
                (cn.chyuan.ai.domain.governance.adapter.IWebhookHttpClient) (url, headers, body) -> {
                    java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(url))
                            .timeout(java.time.Duration.ofSeconds(5))
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body,
                                    java.nio.charset.StandardCharsets.UTF_8));
                    headers.forEach(builder::header);
                    java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient()
                            .send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                    return response.statusCode();
                });

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            // 前两次返回 500 验证重试，之后 200
            if (failuresFirst.incrementAndGet() <= 2) {
                exchange.sendResponseHeaders(500, -1);
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            captured.add(new CapturedRequest(body, Map.copyOf(exchange.getRequestHeaders())));
            exchange.sendResponseHeaders(200, -1);
            try (OutputStream ignored = exchange.getResponseBody()) {
                // 空响应体
            }
        });
        server.start();
    }

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private WebhookEndpointVO endpoint(String url, String eventsJson, String secret) {
        return WebhookEndpointVO.builder()
                .id(1L).name("ops").url(url).secret(secret).enabled(1)
                .events(eventsJson == null ? List.of()
                        : com.alibaba.fastjson.JSON.parseArray(eventsJson, String.class))
                .build();
    }

    @Test
    @DisplayName("投递 — 订阅命中即 POST（重试后成功），签名头与载荷可验证，含治理台深链")
    public void testDeliveryWithSignatureAndDeepLink() throws Exception {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
        when(endpointRepository.findEnabled()).thenReturn(List.of(endpoint(url, "[\"" + BUDGET_EVENT + "\"]", "s3cret")));

        service.publish(BUDGET_EVENT, Map.of("virtualKeyId", 42L, "used", 8L, "hard", 10L));

        waitUntil(() -> !captured.isEmpty());
        CapturedRequest request = captured.poll();
        assertTrue(request.body().contains("\"eventType\":\"" + BUDGET_EVENT + "\""), "载荷含事件类型");
        assertTrue(request.body().contains("\"virtualKeyId\":42"), "载荷含数据");
        assertTrue(request.body().contains("/keys?id=42"), "深链指向密钥页");
        List<String> timestamps = request.headers().get("X-gw-timestamp");
        assertNotNull(timestamps, "签名时间戳头存在（HttpServer 头名小写）");
        List<String> signatures = request.headers().get("X-gw-signature");
        assertNotNull(signatures, "签名头存在");
        String expected = WebhookEventDeliveryService.hmacSha256("s3cret", timestamps.get(0) + "." + request.body());
        assertEquals(expected, signatures.get(0), "HMAC 签名可复验");
    }

    @Test
    @DisplayName("订阅过滤 — 未订阅该事件类型的端点不投递")
    public void testSubscriptionFilter() throws Exception {
        when(endpointRepository.findEnabled()).thenReturn(List.of(
                endpoint("http://127.0.0.1:1/never", "[\"CHANNEL_AUTO_DISABLED\"]", null)));

        service.publish(BUDGET_EVENT, Map.of("virtualKeyId", 42L));

        verify(httpClient, never()).postJson(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("无启用端点 — 零出站开销")
    public void testNoEndpointsNoop() {
        when(endpointRepository.findEnabled()).thenReturn(List.of());
        service.publish(BUDGET_EVENT, Map.of());
        verifyNoInteractions(httpClient);
    }

    @Test
    @DisplayName("重试 — 非 2xx 退避重试至成功（mock 客户端口径）")
    public void testRetryUntilSuccess() throws Exception {
        // setUp 注入了真实出站，本例换回 mock 断言重试次数
        org.springframework.test.util.ReflectionTestUtils.setField(service, "httpClient", httpClient);
        when(endpointRepository.findEnabled()).thenReturn(List.of(
                endpoint("http://receiver.local/hook", "[\"" + BUDGET_EVENT + "\"]", null)));
        when(httpClient.postJson(anyString(), any(), anyString()))
                .thenReturn(500)
                .thenReturn(500)
                .thenReturn(200);

        service.publish(BUDGET_EVENT, Map.of("virtualKeyId", 42L));

        verify(httpClient, timeout(3000).times(3)).postJson(anyString(), any(), anyString());
    }
}

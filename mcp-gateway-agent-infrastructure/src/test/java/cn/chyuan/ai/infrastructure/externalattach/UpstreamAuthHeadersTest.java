package cn.chyuan.ai.infrastructure.externalattach;

import cn.chyuan.ai.domain.externalattach.model.valobj.ExternalAttachVO;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上游鉴权类型测试（工单 0061：四类型头解析 + OAuth token 获取/缓存）
 */
@DisplayName("上游鉴权类型测试")
public class UpstreamAuthHeadersTest {

    private HttpServer tokenServer;
    private final ConcurrentLinkedDeque<String> tokenRequests = new ConcurrentLinkedDeque<>();
    private final AtomicInteger tokenIssues = new AtomicInteger(0);

    @BeforeEach
    public void setUp() throws Exception {
        tokenServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tokenServer.createContext("/token", exchange -> {
            tokenRequests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String token = "tok-" + tokenIssues.incrementAndGet();
            String body = "{\"access_token\":\"" + token + "\",\"expires_in\":3600}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        tokenServer.start();
    }

    @AfterEach
    public void tearDown() {
        if (tokenServer != null) {
            tokenServer.stop(0);
        }
    }

    private ExternalAttachVO attach(String authType, String authConfig, String legacyApiKey) {
        return ExternalAttachVO.builder()
                .id(1L).gatewayId("g").attachName("a")
                .transportType(ExternalAttachVO.TRANSPORT_STREAMABLE_HTTP)
                .endpoint("http://upstream.local/mcp")
                .authType(authType).authConfig(authConfig).apiKey(legacyApiKey)
                .build();
    }

    @Test
    @DisplayName("NONE — 不注入；BEARER — authConfig.token 优先、存量 apiKey 兜底")
    public void testNoneAndBearer() {
        UpstreamAuthHeaders headers = new UpstreamAuthHeaders(new UpstreamOAuthTokenManager());
        assertTrue(headers.headersFor(attach(ExternalAttachVO.AUTH_TYPE_NONE, null, "legacy")).isEmpty());
        assertEquals(Map.of("Authorization", "Bearer cfg-token"),
                headers.headersFor(attach(ExternalAttachVO.AUTH_TYPE_BEARER,
                        "{\"token\":\"cfg-token\"}", "legacy")));
        assertEquals(Map.of("Authorization", "Bearer legacy-key"),
                headers.headersFor(attach(ExternalAttachVO.AUTH_TYPE_BEARER, null, "legacy-key")));
    }

    @Test
    @DisplayName("HEADER — 自定义 header 名值注入；配置不完整抛错")
    public void testHeaderType() {
        UpstreamAuthHeaders headers = new UpstreamAuthHeaders(new UpstreamOAuthTokenManager());
        assertEquals(Map.of("X-Api-Key", "secret"),
                headers.headersFor(attach(ExternalAttachVO.AUTH_TYPE_HEADER,
                        "{\"name\":\"X-Api-Key\",\"value\":\"secret\"}", null)));
        assertThrows(IllegalStateException.class, () -> headers.headersFor(
                attach(ExternalAttachVO.AUTH_TYPE_HEADER, "{\"name\":\"X\"}", null)));
    }

    @Test
    @DisplayName("OAUTH_CC — client_credentials 取 token 并缓存（一次获取两次使用）")
    public void testOAuthClientCredentialsCached() {
        String tokenUrl = "http://127.0.0.1:" + tokenServer.getAddress().getPort() + "/token";
        UpstreamAuthHeaders headers = new UpstreamAuthHeaders(new UpstreamOAuthTokenManager());
        String authConfig = "{\"tokenUrl\":\"" + tokenUrl
                + "\",\"clientId\":\"cid\",\"clientSecret\":\"cs\",\"scope\":\"mcp.read\"}";

        Map<String, String> first = headers.headersFor(attach(ExternalAttachVO.AUTH_TYPE_OAUTH_CC, authConfig, null));
        Map<String, String> second = headers.headersFor(attach(ExternalAttachVO.AUTH_TYPE_OAUTH_CC, authConfig, null));

        assertEquals("Bearer tok-1", first.get("Authorization"));
        assertEquals("Bearer tok-1", second.get("Authorization"), "缓存命中不重复取 token");
        assertEquals(1, tokenRequests.size());
        assertTrue(tokenRequests.peek().contains("grant_type=client_credentials"));
        assertTrue(tokenRequests.peek().contains("client_id=cid"));
        assertTrue(tokenRequests.peek().contains("scope=mcp.read"));
    }

    @Test
    @DisplayName("OAUTH_CC 配置不完整 — 明确报错")
    public void testOAuthIncompleteConfig() {
        UpstreamAuthHeaders headers = new UpstreamAuthHeaders(new UpstreamOAuthTokenManager());
        assertThrows(IllegalStateException.class, () -> headers.headersFor(
                attach(ExternalAttachVO.AUTH_TYPE_OAUTH_CC, "{\"tokenUrl\":\"http://x\"}", null)));
    }
}

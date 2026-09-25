package cn.chyuan.ai.domain.nginxkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 路由配置内核测试（工单 0923-0930 DE1-DE8，nginx 思想）。
 * 配置解析/location 匹配优先级/rewrite 重匹配/upstream 加权轮询/代理路径拼接/限速突发/日志占位符/端口编排。
 */
class NginxKernelTest {

    private static final String CONFIG = """
            upstream backend {
              server 10.0.0.1:8080 weight=3;
              server 10.0.0.2:8080 weight=1;
            }
            server {
              listen 80;
              location = /health { return 200; }
              location /api/ { proxy_pass http://backend/; limit_req rate=5 burst=2; }
              location ~ \\.php$ { return 403; }
              location /old/ { rewrite ^/old/(.*)$ /api/$1 last; }
              location /api/health { return 200; }
            }
            """;

    @Test
    void configParseBlocks() {
        NginxConfig config = NginxConfig.parse(CONFIG);
        assertEquals(1, config.servers.size());
        assertEquals(1, config.upstreams.size());
        NginxConfig.ServerBlock server = config.servers.get(0);
        assertEquals(80, server.listenPort);
        assertEquals(5, server.locations.size(), "五个 location 全解析");
        NginxConfig.Upstream backend = config.upstreams.get("backend");
        assertEquals(2, backend.servers.size());
        assertEquals(3, backend.servers.get(0).weight());
        assertThrows(IllegalArgumentException.class, () -> NginxConfig.parse("upstream a { }"), "缺 server 块拒绝");
    }

    @Test
    void locationMatchPriority() {
        NginxPort port = NginxPort.inMemory(CONFIG);
        assertEquals("=", port.route("/health").modifier, "精确优先");
        assertEquals("/api/", port.route("/api/users").pattern, "前缀匹配");
        assertTrue(port.route("/index.php").pattern.contains(".php"), "正则命中");
        assertEquals("/api/health", port.route("/api/health").pattern, "正则优先于前缀");
        assertNull(port.route("/nope"), "未命中 404");
    }

    @Test
    void rewriteRematch() {
        NginxPort port = NginxPort.inMemory(CONFIG);
        NginxConfig.Location rewritten = port.route("/old/users");
        assertEquals("/api/", rewritten.pattern, "rewrite 后按新路径重匹配");
    }

    @Test
    void upstreamWeightedRoundRobin() {
        NginxPort port = NginxPort.inMemory(CONFIG);
        List<String> picks = new java.util.ArrayList<>();
        for (int seq = 0; seq < 4; seq++) {
            picks.add(port.forward("/api/x", seq).upstreamServer());
        }
        assertEquals(3, picks.stream().filter("10.0.0.1:8080"::equals).count(), "权重 3");
        assertEquals(1, picks.stream().filter("10.0.0.2:8080"::equals).count(), "权重 1");
    }

    @Test
    void forwardPathSplicing() {
        NginxPort port = NginxPort.inMemory(CONFIG);
        NginxRouter.Forward forward = port.forward("/api/users", 0);
        assertEquals("http://10.0.0.1:8080/users", forward.url(), "剥离 /api/ 前缀拼接 base /");
        NginxConfig.Location plain = NginxConfig.parse(CONFIG).servers.get(0).locations.get(1);
        assertEquals("/api/", plain.pattern);
    }

    @Test
    void rateLimitBurst() {
        RateLimiter limiter = new RateLimiter(2, 2);
        assertTrue(limiter.allow(), "突发 1");
        assertTrue(limiter.allow(), "突发 2");
        assertFalse(limiter.allow(), "令牌耗尽拒绝");
        limiter.advance(500);
        assertTrue(limiter.allow(), "500ms 补 1 令牌");
        assertFalse(limiter.allow());
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(0, 1), "非法速率拒绝");
    }

    @Test
    void accessLogFormat() {
        String line = NginxRouter.formatLog("$remote_addr [$time_local] \"$request\" $status $upstream_addr",
                Map.of("remote_addr", "1.2.3.4", "time_local", "26/Sep/2026", "request", "GET /x",
                        "status", "200", "upstream_addr", "10.0.0.1:8080"));
        assertTrue(line.startsWith("1.2.3.4 [26/Sep/2026]"));
        assertTrue(line.contains("\"GET /x\" 200 10.0.0.1:8080"));
    }

    @Test
    void portConfigRejects() {
        assertThrows(IllegalArgumentException.class, () -> NginxPort.inMemory("# only comment"),
                "缺 server 块拒绝");
    }
}

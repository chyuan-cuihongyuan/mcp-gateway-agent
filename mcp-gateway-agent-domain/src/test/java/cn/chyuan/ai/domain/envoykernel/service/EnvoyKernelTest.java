package cn.chyuan.ai.domain.envoykernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 代理自愈内核测试（工单 0936-0943 EA1-EA8，envoy 思想）。
 * 集群端点表/健康阈值翻转/异常点剔除冷却/熔断池/重试预算退避/过滤器链/加权最小请求/端口组合管线。
 */
class EnvoyKernelTest {

    @Test
    void clusterAndEndpointRegistry() {
        EnvoyCluster cluster = new EnvoyCluster();
        cluster.addCluster("web");
        assertThrows(IllegalArgumentException.class, () -> cluster.addCluster("web"), "重复集群拒绝");
        assertThrows(IllegalArgumentException.class, () -> cluster.addEndpoint("missing", "a", 1), "未知集群拒绝");
        assertThrows(IllegalArgumentException.class, () -> cluster.addEndpoint("web", "a", 0), "权重非法拒绝");
        cluster.addEndpoint("web", "a", 5);
        assertThrows(IllegalArgumentException.class, () -> cluster.addEndpoint("web", "a", 1), "重复端点拒绝");
        assertEquals(1, cluster.endpoints("web").size());
        assertThrows(IllegalArgumentException.class, () -> cluster.endpoints("empty"), "空集群拒绝");
    }

    @Test
    void healthThresholdFlip() {
        EnvoyCluster cluster = new EnvoyCluster();
        cluster.addCluster("web");
        cluster.addEndpoint("web", "a", 1);
        EnvoyCluster.Endpoint a = cluster.mutable("web").get(0);
        HealthChecker health = new HealthChecker(2, 2);
        health.probe(a, false);
        assertTrue(a.healthy, "未达不健康阈值维持原态");
        health.probe(a, false);
        assertFalse(a.healthy, "连续失败达阈值翻转");
        health.probe(a, true);
        assertFalse(a.healthy, "未达健康阈值维持不健康");
        int seq = health.probe(a, true);
        assertTrue(a.healthy, "连续成功达阈值恢复");
        assertEquals(4, seq, "探活请求序递增");
        assertEquals(4, health.probes());
    }

    @Test
    void outlierEjectCooldownProbe() {
        EnvoyCluster cluster = new EnvoyCluster();
        cluster.addCluster("web");
        cluster.addEndpoint("web", "a", 1);
        EnvoyCluster.Endpoint a = cluster.mutable("web").get(0);
        OutlierDetector detector = new OutlierDetector(2, 1);
        detector.report(a, 503);
        assertEquals("active", detector.state(a), "未达阈值不剔除");
        detector.report(a, 503);
        assertEquals("cooling", detector.state(a), "连续 5xx 弹出进入冷却");
        detector.tick(a);
        assertEquals("probing", detector.state(a), "冷却期满进入试探");
        detector.report(a, 200);
        assertEquals("active", detector.state(a), "试探成功回归");
        detector.report(a, 503);
        assertEquals("active", detector.state(a), "成功已复位计数");
    }

    @Test
    void circuitBreakerBudgetAndRelease() {
        CircuitBreaker breaker = new CircuitBreaker(1, 1);
        assertEquals(CircuitBreaker.Slot.CONNECTED, breaker.acquire());
        assertEquals(CircuitBreaker.Slot.PENDING, breaker.acquire(), "连接满进入挂起");
        assertEquals(CircuitBreaker.Slot.REJECTED, breaker.acquire(), "挂起也满拒绝");
        assertEquals(1, breaker.active());
        assertEquals(1, breaker.pending());
        breaker.release();
        assertEquals(1, breaker.active(), "释放时挂起优先晋升");
        assertEquals(0, breaker.pending());
        breaker.release();
        assertEquals(0, breaker.active());
        assertThrows(IllegalStateException.class, breaker::release, "无连接可释放拒绝");
    }

    @Test
    void retryBudgetAndBackoffSequence() {
        RetryPolicy retry = new RetryPolicy(2, 100, 100L);
        assertEquals(100L, retry.backoffMillis(1));
        assertEquals(200L, retry.backoffMillis(2));
        assertEquals(400L, retry.backoffMillis(3), "指数退避序列");
        assertThrows(IllegalArgumentException.class, () -> retry.backoffMillis(0), "非法次数拒绝");
        retry.onRequest();
        assertTrue(retry.allowRetry());
        retry.onRetry();
        assertFalse(retry.allowRetry(), "预算（1 请求 100% = 1 次）用尽拒绝超额");
        retry.onRequest();
        assertTrue(retry.allowRetry(), "主请求数增长预算回升");
        retry.onSuccess();
        assertEquals(0, retry.retries(), "成功停重试清零");
    }

    @Test
    void filterChainOrderAndShortCircuit() {
        FilterChain chain = new FilterChain();
        StringBuilder trace = new StringBuilder();
        chain.add(ctx -> trace.append("1:").append(ctx.request).append(";"));
        chain.add(ctx -> ctx.response = "cached");
        chain.add(ctx -> trace.append("3:"));
        assertEquals(3, chain.size());
        FilterChain.Context ctx = chain.execute("req");
        assertEquals("1:req;", trace.toString(), "注册顺序执行");
        assertEquals("cached", ctx.response, "短路响应即停");
        assertEquals("req", ctx.request, "透传保留请求");
        FilterChain passthrough = new FilterChain();
        passthrough.add(ctx2 -> { });
        FilterChain.Context clean = passthrough.execute("q");
        assertNull(clean.response, "无短路继续透传");
    }

    @Test
    void weightedLeastRequestPick() {
        EnvoyCluster cluster = new EnvoyCluster();
        cluster.addCluster("web");
        cluster.addEndpoint("web", "light-heavy", 5);
        cluster.addEndpoint("web", "light-small", 1);
        cluster.addEndpoint("web", "busy", 5);
        List<EnvoyCluster.Endpoint> eps = cluster.mutable("web");
        eps.get(2).inFlight = 2;
        LoadBalancer balancer = new LoadBalancer(cluster);
        assertSame(eps.get(0), balancer.pick("web"), "活跃相同取权重大者");
        eps.get(0).inFlight = 3;
        assertSame(eps.get(1), balancer.pick("web"), "活跃最小优先");
        eps.get(0).inFlight = 0;
        eps.get(1).inFlight = 0;
        eps.forEach(e -> e.healthy = false);
        assertThrows(IllegalArgumentException.class, () -> balancer.pick("web"), "全部不可用拒绝");
    }

    @Test
    void portCompositePipeline() {
        EnvoyPort port = EnvoyPort.inMemory();
        port.discover("web", List.of("a", "b"));
        port.discover("web", List.of("a", "b"));
        InMemoryEnvoy impl = (InMemoryEnvoy) port;
        assertEquals(2, impl.cluster().endpoints("web").size(), "发现幂等");

        String ok = port.exchange("web", "GET /", (endpoint, request) ->
                endpoint.equals("a") ? 503 : 200);
        assertEquals("b 200 attempts=2", ok, "5xx 预算内重试换点成功停重试");

        Map<String, Integer> data = port.reconcileData("web");
        assertEquals(2, data.get("healthy"), "调节观测：健康数");
        assertEquals(0, data.get("ejected"), "调节观测：剔除数");
        assertEquals(0, data.get("inFlight"), "调节观测：活跃请求数");

        String allFail = port.exchange("web", "GET /", (endpoint, request) -> 500);
        assertEquals("b 500 attempts=3", allFail, "连续 5xx 触发剔除换点，预算耗尽返回末次结果");

        Map<String, Integer> afterFail = port.reconcileData("web");
        assertEquals(2, afterFail.get("ejected"), "连续 5xx 两端点均剔除");

        EnvoyCluster.Endpoint b = impl.cluster().mutable("web").get(1);
        impl.outlier().tick(b);
        impl.outlier().report(b, 200);
        impl.health().probe(b, true);
        impl.health().probe(b, true);
        assertFalse(b.ejected, "冷却试探成功回归");
        assertTrue(b.healthy, "健康探活恢复");

        impl.filters().add(ctx -> ctx.response = "blocked");
        assertEquals("b 200 attempts=1", port.exchange("web", "GET /secret", (endpoint, request) -> 200),
                "过滤器链短路直接响应");

        assertThrows(IllegalArgumentException.class, () -> port.exchange("nope", "GET /", (e, r) -> 200),
                "未知集群拒绝");
    }
}

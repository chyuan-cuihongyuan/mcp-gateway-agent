package cn.chyuan.ai.infrastructure.utils;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 网关指标收口测试（工单 0067：requests.total/inflight 配对/latency/channel.state）
 */
@DisplayName("网关指标收口测试")
public class GatewayMetricsTest {

    private GatewayMetrics metricsWithRegistry() {
        GatewayMetrics metrics = new GatewayMetrics();
        org.springframework.test.util.ReflectionTestUtils.setField(metrics, "registry", new SimpleMeterRegistry());
        return metrics;
    }

    @Test
    @DisplayName("requests.total — 标签四维计数")
    public void testCountRequest() {
        GatewayMetrics metrics = metricsWithRegistry();
        metrics.countRequest("MCP", "POST", "200", "");
        metrics.countRequest("MCP", "POST", "200", "");
        metrics.countRequest("LLM", "POST", "429", "quota");
        SimpleMeterRegistry registry = (SimpleMeterRegistry)
                org.springframework.test.util.ReflectionTestUtils.getField(metrics, "registry");
        assertEquals(2.0, registry.counter("gateway.requests.total",
                "traffic", "MCP", "method", "POST", "status", "200", "reason", "").count());
        assertEquals(1.0, registry.counter("gateway.requests.total",
                "traffic", "LLM", "method", "POST", "status", "429", "reason", "quota").count());
    }

    @Test
    @DisplayName("inflight — 进/出严格配对（并发 3 出 3 归零）")
    public void testInflightPairing() {
        GatewayMetrics metrics = metricsWithRegistry();
        metrics.inflightStart("MCP");
        metrics.inflightStart("MCP");
        metrics.inflightStart("MCP");
        SimpleMeterRegistry registry = (SimpleMeterRegistry)
                org.springframework.test.util.ReflectionTestUtils.getField(metrics, "registry");
        assertEquals(3.0, registry.get("gateway.requests.inflight").tag("traffic", "MCP").gauge().value());
        metrics.inflightEnd("MCP");
        metrics.inflightEnd("MCP");
        metrics.inflightEnd("MCP");
        assertEquals(0.0, registry.get("gateway.requests.inflight").tag("traffic", "MCP").gauge().value());
    }

    @Test
    @DisplayName("latency — 直方图记录；channel.state — 三态回写")
    public void testLatencyAndChannelState() {
        GatewayMetrics metrics = metricsWithRegistry();
        metrics.recordLatency("LLM", 120);
        metrics.channelState("oil", 1);
        SimpleMeterRegistry registry = (SimpleMeterRegistry)
                org.springframework.test.util.ReflectionTestUtils.getField(metrics, "registry");
        assertEquals(1, registry.get("gateway.latency").tag("traffic", "LLM").timer().count());
        assertEquals(1.0, registry.get("gateway.channel.state").tag("channel", "oil").gauge().value());
        metrics.channelState("oil", 0);
        assertEquals(0.0, registry.get("gateway.channel.state").tag("channel", "oil").gauge().value());
    }
}

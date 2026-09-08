package cn.chyuan.ai.infrastructure.utils;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 网关指标收口（工单 0067，LiteLLM Prometheus 指标面口径）
 *
 * <p>gateway.* 指标族唯一触点：requests.total{traffic,method,status,reason} /
 * requests.inflight{traffic} / latency（直方图，端到端）/ channel.state{channel}
 * （0 启用 / 1 自动禁用 / 2 手动禁用）。打点全部内存原子操作，无同步 IO。
 *
 * @author chyuan
 */
@Component
public class GatewayMetrics {

    @Autowired(required = false)
    private MeterRegistry registry;

    /** traffic → 在途计数 */
    private final ConcurrentHashMap<String, AtomicLong> inflight = new ConcurrentHashMap<>();

    /** channel → 状态值（0/1/2） */
    private final ConcurrentHashMap<String, AtomicLong> channelState = new ConcurrentHashMap<>();

    public void countRequest(String traffic, String method, String status, String reason) {
        if (registry == null) {
            return;
        }
        registry.counter("gateway.requests.total",
                "traffic", orEmpty(traffic), "method", orEmpty(method),
                "status", orEmpty(status), "reason", orEmpty(reason)).increment();
    }

    public void inflightStart(String traffic) {
        if (registry == null) {
            return;
        }
        AtomicLong counter = inflight.computeIfAbsent(orEmpty(traffic), t -> {
            AtomicLong created = new AtomicLong();
            registry.gauge("gateway.requests.inflight", java.util.List.of(io.micrometer.core.instrument.Tag.of("traffic", t)), created);
            return created;
        });
        counter.incrementAndGet();
    }

    public void inflightEnd(String traffic) {
        AtomicLong counter = inflight.get(orEmpty(traffic));
        if (counter != null) {
            counter.decrementAndGet();
        }
    }

    public void recordLatency(String traffic, long millis) {
        if (registry == null) {
            return;
        }
        Timer.builder("gateway.latency")
                .tag("traffic", orEmpty(traffic))
                .publishPercentileHistogram()
                .register(registry)
                .record(millis, TimeUnit.MILLISECONDS);
    }

    /** 渠道状态三态：0 启用 / 1 自动禁用 / 2 手动禁用（变更点回写） */
    public void channelState(String channel, int state) {
        if (registry == null) {
            return;
        }
        channelState.computeIfAbsent(channel, c -> {
            AtomicLong created = new AtomicLong(0);
            registry.gauge("gateway.channel.state",
                    java.util.List.of(io.micrometer.core.instrument.Tag.of("channel", c)), created);
            return created;
        }).set(state);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}

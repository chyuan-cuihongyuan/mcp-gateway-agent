package cn.chyuan.ai.domain.session.service.management;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 会话生命周期指标（SELFLOOP2 loop-215，J04）。
 * mcp_session_active（Gauge 活跃水线）+ created/expired/closed 三个 Counter。
 * registry 缺席（null）时全 no-op，不影响主流程。
 */
@Component
public class SessionMetrics {

    private final MeterRegistry registry;
    private final AtomicInteger activeGaugeValue = new AtomicInteger(0);
    private volatile boolean gaugeRegistered = false;

    /** 无 registry 环境的共享 no-op 实例 */
    public static SessionMetrics noOp() {
        return new SessionMetrics(null);
    }

    private final Counter createdCounter;
    private final Counter expiredCounter;
    private final Counter closedCounter;

    public SessionMetrics(MeterRegistry registry) {
        this.registry = registry;
        if (registry != null) {
            this.createdCounter = Counter.builder("mcp_session_created_total")
                    .description("会话创建总数").register(registry);
            this.expiredCounter = Counter.builder("mcp_session_expired_total")
                    .description("会话过期清理总数").register(registry);
            this.closedCounter = Counter.builder("mcp_session_closed_total")
                    .description("会话显式关闭总数").register(registry);
        } else {
            this.createdCounter = null;
            this.expiredCounter = null;
            this.closedCounter = null;
        }
    }

    /** 绑定活跃会话 map 作为 Gauge 数据源（幂等，多实例下重复注册吞掉） */
    public void bindActiveMap(Map<String, ?> activeSessions) {
        if (registry == null || gaugeRegistered) {
            return;
        }
        synchronized (this) {
            if (gaugeRegistered) {
                return;
            }
            try {
                Gauge.builder("mcp_session_active", activeSessions, Map::size)
                        .description("当前活跃会话数")
                        .register(registry);
            } catch (IllegalArgumentException alreadyRegistered) {
                // 同名 Gauge 已注册（如上下文重建），保留首个
            }
            gaugeRegistered = true;
        }
    }

    public void recordCreated() {
        if (createdCounter != null) {
            createdCounter.increment();
        }
    }

    public void recordExpired(int count) {
        if (expiredCounter != null && count > 0) {
            expiredCounter.increment(count);
        }
    }

    public void recordClosed() {
        if (closedCounter != null) {
            closedCounter.increment();
        }
    }
}

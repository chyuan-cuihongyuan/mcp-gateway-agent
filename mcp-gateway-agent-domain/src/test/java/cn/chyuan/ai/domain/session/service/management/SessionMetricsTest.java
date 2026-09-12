package cn.chyuan.ai.domain.session.service.management;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 会话生命周期指标契约测试（SELFLOOP2 loop-215）：
 * Gauge 水线与三 Counter 的累计行为锁定；无 registry 环境全 no-op。
 */
@DisplayName("SessionMetrics 生命周期指标契约")
class SessionMetricsTest {

    @Test
    @DisplayName("Gauge 水线随 map 尺寸变化；三 Counter 正确累计")
    void gaugeAndCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SessionMetrics metrics = new SessionMetrics(registry);
        ConcurrentHashMap<String, Object> active = new ConcurrentHashMap<>();

        metrics.bindActiveMap(active);
        active.put("s1", new Object());
        active.put("s2", new Object());
        assertEquals(2.0, registry.get("mcp_session_active").gauge().value());

        metrics.recordCreated();
        metrics.recordCreated();
        metrics.recordExpired(3);
        metrics.recordClosed();

        assertEquals(2.0, registry.get("mcp_session_created_total").counter().count());
        assertEquals(3.0, registry.get("mcp_session_expired_total").counter().count());
        assertEquals(1.0, registry.get("mcp_session_closed_total").counter().count());
    }

    @Test
    @DisplayName("bindActiveMap 幂等：重复绑定不抛错")
    void bindIdempotent() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SessionMetrics metrics = new SessionMetrics(registry);
        ConcurrentHashMap<String, Object> active = new ConcurrentHashMap<>();

        metrics.bindActiveMap(active);
        assertDoesNotThrow(() -> metrics.bindActiveMap(active));
    }

    @Test
    @DisplayName("no-op 实例：无 registry 全部静默")
    void noOpInstance() {
        SessionMetrics noOp = SessionMetrics.noOp();
        assertDoesNotThrow(() -> {
            noOp.bindActiveMap(new ConcurrentHashMap<String, Object>());
            noOp.recordCreated();
            noOp.recordExpired(2);
            noOp.recordClosed();
        });
    }

    @Test
    @DisplayName("服务三路径埋点：创建→created+active，显式删→closed，清理→expired")
    void serviceWiringPaths() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SessionMetrics metrics = new SessionMetrics(registry);
        SessionManagementService service = new SessionManagementService();
        ReflectionTestUtils.setField(service, "sessionMetrics", metrics);
        ReflectionTestUtils.setField(service, "sessionTimeoutMinutes", 30L);

        var vo = service.createSession("gw-1", "key");
        assertEquals(1.0, registry.get("mcp_session_created_total").counter().count());
        assertEquals(1.0, registry.get("mcp_session_active").gauge().value());

        service.removeSession(vo.getSessionId());
        assertEquals(1.0, registry.get("mcp_session_closed_total").counter().count());
        assertEquals(0.0, registry.get("mcp_session_active").gauge().value());

        service.cleanupExpiredSessions();
        assertEquals(0.0, registry.get("mcp_session_expired_total").counter().count());
    }
}

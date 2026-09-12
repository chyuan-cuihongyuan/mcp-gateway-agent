package cn.chyuan.ai.domain.governance.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 热点参数限流单测（工单 0226 AD7）：滑动窗口计数/维度隔离/阈值边界。
 */
class HotspotParamLimiterTest {

    @Test
    void 窗口内计数与阈值() {
        AtomicLong clock = new AtomicLong(0);
        HotspotParamLimiter limiter = new HotspotParamLimiter(1000, clock::get);
        // 阈值 5：前 5 次放行，第 6 次拒绝
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("model:gpt-x", 5));
        }
        assertFalse(limiter.tryAcquire("model:gpt-x", 5));
        // 其他维度独立计数
        assertTrue(limiter.tryAcquire("model:glm", 5));
        assertEquals(2, limiter.dimensionCount());
    }

    @Test
    void 窗口推进恢复放行() {
        AtomicLong clock = new AtomicLong(0);
        HotspotParamLimiter limiter = new HotspotParamLimiter(1000, clock::get);
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire("vk:1", 3));
        }
        assertFalse(limiter.tryAcquire("vk:1", 3));
        // 时间推进 2 个窗口：旧计数滑出
        clock.addAndGet(2_000_000_000L);
        assertTrue(limiter.tryAcquire("vk:1", 3), "窗口滑出后应恢复放行");
    }

    @Test
    void 空维度恒放行() {
        HotspotParamLimiter limiter = new HotspotParamLimiter(1000, () -> 0L);
        assertTrue(limiter.tryAcquire(null, 0));
        assertTrue(limiter.tryAcquire("", 0));
        assertEquals(0, limiter.dimensionCount());
    }
}

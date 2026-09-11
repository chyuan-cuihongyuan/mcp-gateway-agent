package cn.chyuan.ai.domain.governance.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 半开熔断状态机单元测试（工单 0177 Y1）— 全流转、试探单发、冷却边界、参数校验。
 */
@DisplayName("半开熔断状态机测试")
class HalfOpenBreakerTest {

    @Test
    @DisplayName("全流转 — 连败转 OPEN、冷却满转 HALF_OPEN、试探成功×2 回 CLOSED")
    public void testFullCycle() {
        AtomicLong now = new AtomicLong(1_000_000L);
        HalfOpenBreaker breaker = new HalfOpenBreaker(3, 2, 5_000L, now::get);

        // CLOSED 放行，3 次失败转 OPEN
        assertTrue(breaker.tryAcquire());
        breaker.onFailure();
        breaker.onFailure();
        assertTrue(breaker.tryAcquire());
        breaker.onFailure();
        assertEquals(HalfOpenBreaker.State.OPEN, breaker.state());

        // OPEN 冷却未满：拒绝（试探单发）
        assertFalse(breaker.tryAcquire());

        // 冷却期满 → HALF_OPEN 放行试探
        now.addAndGet(5_001L);
        assertTrue(breaker.tryAcquire());
        assertEquals(HalfOpenBreaker.State.HALF_OPEN, breaker.state());
        // 试探位被占：其余请求拒绝
        assertFalse(breaker.tryAcquire());
        // 试探成功 ×1（未达 2）仍 HALF_OPEN，再放行试探
        breaker.onSuccess();
        assertTrue(breaker.tryAcquire());
        breaker.onSuccess();
        assertEquals(HalfOpenBreaker.State.CLOSED, breaker.state());
    }

    @Test
    @DisplayName("半开试探失败 — 回 OPEN 重新计时")
    public void testHalfOpenFailureReopens() {
        AtomicLong now = new AtomicLong(0L);
        HalfOpenBreaker breaker = new HalfOpenBreaker(1, 1, 100L, now::get);

        assertTrue(breaker.tryAcquire());
        breaker.onFailure();
        assertEquals(HalfOpenBreaker.State.OPEN, breaker.state());

        now.addAndGet(101L);
        assertTrue(breaker.tryAcquire());
        assertEquals(HalfOpenBreaker.State.HALF_OPEN, breaker.state());
        breaker.onFailure();
        assertEquals(HalfOpenBreaker.State.OPEN, breaker.state());
        // 重新计时：需再次等冷却
        assertFalse(breaker.tryAcquire());
    }

    @Test
    @DisplayName("CLOSED 成功清零连败计数")
    public void testSuccessResetsFailures() {
        HalfOpenBreaker breaker = new HalfOpenBreaker(3, 1, 1000L, () -> 0L);
        breaker.onFailure();
        breaker.onFailure();
        assertTrue(breaker.tryAcquire());
        breaker.onSuccess();
        breaker.onFailure();
        breaker.onFailure();
        assertEquals(HalfOpenBreaker.State.CLOSED, breaker.state());
    }

    @Test
    @DisplayName("参数非法拒绝")
    public void testInvalidParams() {
        assertThrows(IllegalArgumentException.class, () -> new HalfOpenBreaker(0, 1, 100L));
        assertThrows(IllegalArgumentException.class, () -> new HalfOpenBreaker(1, 0, 100L));
        assertThrows(IllegalArgumentException.class, () -> new HalfOpenBreaker(1, 1, -1L));
    }
}

package cn.chyuan.ai.domain.governance.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 水位自适应单测（工单 0227 AD8）：三档判定/阈值钳制/RED 拒新/YELLOW 对半降额。
 */
class AdaptiveGuardTest {

    private static final AdaptiveGuard.Thresholds T = AdaptiveGuard.Thresholds.DEFAULT;

    @Test
    void 三档判定() {
        assertEquals(AdaptiveGuard.LEVEL_GREEN,
                AdaptiveGuard.grade(new AdaptiveGuard.LoadWatermark(0.5, 0), T));
        assertEquals(AdaptiveGuard.LEVEL_YELLOW,
                AdaptiveGuard.grade(new AdaptiveGuard.LoadWatermark(0.7, 0), T));
        assertEquals(AdaptiveGuard.LEVEL_GREEN,
                AdaptiveGuard.grade(new AdaptiveGuard.LoadWatermark(0.69, 0), T));
        assertEquals(AdaptiveGuard.LEVEL_RED,
                AdaptiveGuard.grade(new AdaptiveGuard.LoadWatermark(0.9, 0), T));
        // 排队深度维度
        assertEquals(AdaptiveGuard.LEVEL_YELLOW,
                AdaptiveGuard.grade(new AdaptiveGuard.LoadWatermark(0.1, 50), T));
        assertEquals(AdaptiveGuard.LEVEL_RED,
                AdaptiveGuard.grade(new AdaptiveGuard.LoadWatermark(0.1, 100), T));
        // 水位钳制 [0,1]
        assertEquals(AdaptiveGuard.LEVEL_RED,
                AdaptiveGuard.grade(new AdaptiveGuard.LoadWatermark(5, 0), T));
    }

    @Test
    void 准入策略() {
        assertTrue(AdaptiveGuard.shouldAdmit(AdaptiveGuard.LEVEL_GREEN, 1));
        assertTrue(AdaptiveGuard.shouldAdmit(AdaptiveGuard.LEVEL_YELLOW, 2), "偶数序放行");
        assertFalse(AdaptiveGuard.shouldAdmit(AdaptiveGuard.LEVEL_YELLOW, 3), "奇数序降额");
        assertFalse(AdaptiveGuard.shouldAdmit(AdaptiveGuard.LEVEL_RED, 2), "RED 恒拒");
        assertFalse(AdaptiveGuard.shouldReject(AdaptiveGuard.LEVEL_YELLOW));
        assertTrue(AdaptiveGuard.shouldReject(AdaptiveGuard.LEVEL_RED));
    }

    @Test
    void 阈值钳制() {
        // yellow >= red 时 red 被抬升
        AdaptiveGuard.Thresholds bad = new AdaptiveGuard.Thresholds(0.9, 0.8, 0);
        assertTrue(bad.redRatio() > bad.yellowRatio());
        assertEquals(AdaptiveGuard.Thresholds.DEFAULT.maxQueueDepth(), bad.maxQueueDepth());
    }
}

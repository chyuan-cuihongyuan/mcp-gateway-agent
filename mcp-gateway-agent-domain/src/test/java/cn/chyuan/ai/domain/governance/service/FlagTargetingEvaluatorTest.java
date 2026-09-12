package cn.chyuan.ai.domain.governance.service;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 开关目标定向单测（工单 0224 AD5）：白名单优先/百分比 stickiness/边界。
 */
class FlagTargetingEvaluatorTest {

    @Test
    void 白名单优先命中() {
        var targeting = new FlagTargetingEvaluator.Targeting("tenant-a,tenant-b", "user-x", 0);
        assertTrue(FlagTargetingEvaluator.evaluate(targeting, "f", "tenant-a", null));
        assertTrue(FlagTargetingEvaluator.evaluate(targeting, "f", "tenant-b", null));
        assertTrue(FlagTargetingEvaluator.evaluate(targeting, "f", "other", "user-x"));
        assertFalse(FlagTargetingEvaluator.evaluate(targeting, "f", "other", "user-y"));
    }

    @Test
    void 百分比stickiness与边界() {
        var full = new FlagTargetingEvaluator.Targeting("", "", 100);
        assertTrue(FlagTargetingEvaluator.evaluate(full, "f", "t", "u"));
        var zero = new FlagTargetingEvaluator.Targeting("", "", 0);
        assertFalse(FlagTargetingEvaluator.evaluate(zero, "f", "t", "u"));
        // stickiness：同键恒同
        var half = new FlagTargetingEvaluator.Targeting("", "", 50);
        boolean first = FlagTargetingEvaluator.evaluate(half, "f", "tenant-1", "u1");
        for (int i = 0; i < 20; i++) {
            assertEquals(first, FlagTargetingEvaluator.evaluate(half, "f", "tenant-1", "u1"));
        }
        // 均匀性粗校验：1000 键 50% 切流命中在 40%-60%
        int hit = 0;
        for (int i = 0; i < 1000; i++) {
            if (FlagTargetingEvaluator.evaluate(half, "f", "t" + i, "u")) {
                hit++;
            }
        }
        assertTrue(hit > 400 && hit < 600, "50% 切流实际 " + hit);
    }

    @Test
    void 空规则与归一() {
        var empty = new FlagTargetingEvaluator.Targeting(null, null, -5);
        assertTrue(empty.isEmpty());
        assertFalse(FlagTargetingEvaluator.evaluate(empty, "f", "t", "u"));
        // 白名单 CSV 解析（中英逗号 + 空白容忍）
        assertEquals(Set.of("a", "b", "c"), FlagTargetingEvaluator.parseCsv("a, b，c,,"));
    }
}

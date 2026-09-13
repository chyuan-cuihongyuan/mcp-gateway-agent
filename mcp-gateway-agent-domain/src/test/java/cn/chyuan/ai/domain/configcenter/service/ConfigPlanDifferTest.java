package cn.chyuan.ai.domain.configcenter.service;

import cn.chyuan.ai.domain.configcenter.service.ConfigPlanDiffer.PlanDiff;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置 plan 三态 diff 单测（工单 0252 AG2）：增/删/改/不变/风险提示/扁平化。
 */
class ConfigPlanDifferTest {

    @Test
    void 四态分类() {
        PlanDiff diff = ConfigPlanDiffer.diff(
                "{\"a\":1,\"b\":\"x\",\"keep\":9,\"keep2\":8,\"keep3\":7}",
                "{\"a\":1,\"b\":\"y\",\"d\":2,\"keep\":9,\"keep2\":8,\"keep3\":7}");
        assertEquals(1, diff.added());
        assertEquals(0, diff.removed());
        assertEquals(1, diff.changed());
        assertEquals(4, diff.unchanged());
        assertEquals(ConfigPlanDiffer.CHANGED, diff.rows().get(0).change());
        assertTrue(diff.rows().stream().anyMatch(r -> "d".equals(r.path()) && ConfigPlanDiffer.ADDED.equals(r.change())));
        // 6 行中触达 2 行且无删除 → 无风险
        assertFalse(diff.hasRisk());
    }

    @Test
    void 嵌套扁平化路径() {
        PlanDiff diff = ConfigPlanDiffer.diff(
                "{\"ratelimit\":{\"enabled\":false}}",
                "{\"ratelimit\":{\"enabled\":true,\"qps\":10}}");
        assertTrue(diff.rows().stream().anyMatch(r -> "ratelimit.enabled".equals(r.path())
                && ConfigPlanDiffer.CHANGED.equals(r.change())));
        assertTrue(diff.rows().stream().anyMatch(r -> "ratelimit.qps".equals(r.path())
                && ConfigPlanDiffer.ADDED.equals(r.change())));
    }

    @Test
    void 风险提示_删除键与大变更占比() {
        PlanDiff removed = ConfigPlanDiffer.diff("{\"a\":1,\"b\":2}", "{\"a\":1}");
        assertTrue(removed.hasRisk());
        assertTrue(removed.risks().get(0).contains("删除 1 个配置键"));
        // 3 行全变更 → 占比 100% 超 50%
        PlanDiff big = ConfigPlanDiffer.diff("{\"a\":1,\"b\":2,\"c\":3}", "{\"a\":9,\"d\":4,\"e\":5}");
        assertTrue(big.risks().stream().anyMatch(r -> r.contains("50%")));
        // 无变更无风险
        assertFalse(ConfigPlanDiffer.diff("{\"a\":1}", "{\"a\":1}").hasRisk());
    }

    @Test
    void 空与非对称入参() {
        PlanDiff fromEmpty = ConfigPlanDiffer.diff(null, "{\"a\":1}");
        assertEquals(1, fromEmpty.added());
        PlanDiff toEmpty = ConfigPlanDiffer.diff("{\"a\":1}", "");
        assertEquals(1, toEmpty.removed());
        assertThrows(IllegalArgumentException.class, () -> ConfigPlanDiffer.diff("not-json", "{}"));
        assertThrows(IllegalArgumentException.class, () -> ConfigPlanDiffer.diff("{}", "[1,2]"));
    }
}

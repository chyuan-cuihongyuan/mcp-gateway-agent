package cn.chyuan.ai.domain.configcenter.service;

import cn.chyuan.ai.domain.configcenter.service.ConfigHealthEvaluator.ConfigHealthService;
import cn.chyuan.ai.domain.configcenter.service.ConfigHealthEvaluator.HealthInputs;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置健康评估单测（工单 0257 AG7）：四态判定互斥与优先级/汇总计数。
 */
class ConfigHealthEvaluatorTest {

    private final ConfigHealthEvaluator evaluator = new ConfigHealthEvaluator();

    @Test
    void 四态判定与优先级() {
        assertEquals(ConfigHealthEvaluator.HEALTHY, evaluator.evaluate(new HealthInputs(false, false, false, false)));
        assertEquals(ConfigHealthEvaluator.PROGRESSING, evaluator.evaluate(new HealthInputs(true, false, false, false)));
        assertEquals(ConfigHealthEvaluator.DEGRADED, evaluator.evaluate(new HealthInputs(false, true, false, false)));
        assertEquals(ConfigHealthEvaluator.DEGRADED, evaluator.evaluate(new HealthInputs(false, false, true, false)));
        // Suspended 压制一切
        assertEquals(ConfigHealthEvaluator.SUSPENDED,
                evaluator.evaluate(new HealthInputs(true, true, true, true)));
        // Degraded 压制 Progressing
        assertEquals(ConfigHealthEvaluator.DEGRADED,
                evaluator.evaluate(new HealthInputs(true, false, true, false)));
    }

    @Test
    void 服务侧状态维护与汇总() {
        ConfigHealthService service = new ConfigHealthService();
        assertEquals(ConfigHealthEvaluator.HEALTHY, service.statusOf("未登记"));
        service.update("a", new HealthInputs(false, false, false, false));
        service.update("b", new HealthInputs(false, true, false, false));
        service.update("c", new HealthInputs(true, false, false, false));
        service.update("d", new HealthInputs(false, false, false, true));
        assertEquals(ConfigHealthEvaluator.DEGRADED, service.statusOf("b"));
        var summary = service.summary();
        assertEquals(4, summary.total());
        assertEquals(1, summary.healthy());
        assertEquals(1, summary.degraded());
        assertEquals(1, summary.progressing());
        assertEquals(1, summary.suspended());
        assertTrue(service.allStatuses().containsKey("d"));
    }
}

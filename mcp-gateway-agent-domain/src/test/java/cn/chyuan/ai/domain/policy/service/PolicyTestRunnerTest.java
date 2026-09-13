package cn.chyuan.ai.domain.policy.service;

import cn.chyuan.ai.domain.policy.service.PolicyEngine.PolicyStatement;
import cn.chyuan.ai.domain.policy.service.PolicyTestRunner.CaseResult;
import cn.chyuan.ai.domain.policy.service.PolicyTestRunner.PolicyCase;
import cn.chyuan.ai.domain.policy.service.PolicyTestRunner.RunReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 策略测试框架单测（工单 0266 AH6）：通过/结论错/命中错/求值异常四分支。
 */
class PolicyTestRunnerTest {

    private PolicyEngine engine;

    @BeforeEach
    void setUp() {
        engine = new PolicyEngine(new PolicyEngine.InMemoryPolicyStore(), new PolicyDecisionCache(32));
        engine.register(new PolicyStatement(null, "allow-gpt", "*", "/^gpt/", "chat",
                null, PolicyEngine.EFFECT_ALLOW, 10, true, null, "op"));
        engine.register(new PolicyStatement(null, "deny-bronze", "*", "*", "*",
                "env.tier == 'bronze'", PolicyEngine.EFFECT_DENY, 50, true, null, "op"));
    }

    @Test
    void 全过与分支失败() {
        RunReport report = new PolicyTestRunner(engine).run(List.of(
                new PolicyCase("allow-case", "k1", "gpt-5", "chat",
                        Map.of("tier", "gold"), "ALLOW", "allow-gpt"),
                new PolicyCase("deny-case", "k1", "gpt-5", "chat",
                        Map.of("tier", "bronze"), "DENY", "deny-bronze")));
        assertTrue(report.allPassed());
        assertEquals(2, report.total());
        assertEquals(2, report.passed());
    }

    @Test
    void 期望结论错() {
        RunReport report = new PolicyTestRunner(engine).run(List.of(
                new PolicyCase("wrong-decision", "k1", "gpt-5", "chat",
                        Map.of("tier", "gold"), "DENY", null)));
        assertFalse(report.allPassed());
        CaseResult result = report.results().get(0);
        assertEquals("期望 DENY 实际 ALLOW", result.reason());
        assertEquals("ALLOW", result.actualDecision());
    }

    @Test
    void 期望命中错与默认拒绝() {
        RunReport report = new PolicyTestRunner(engine).run(List.of(
                new PolicyCase("wrong-hit", "k1", "gpt-5", "chat",
                        Map.of("tier", "gold"), "ALLOW", "no-such-policy"),
                // 无任何策略命中 → defaultEffect DENY（fail-closed）
                new PolicyCase("no-hit-default-deny", "k9", "unknown-model", "chat",
                        Map.of("tier", "gold"), "ALLOW", null)));
        assertFalse(report.allPassed());
        assertEquals(2, report.failed());
        assertTrue(report.results().get(0).reason().contains("期望命中"));
        assertEquals("DENY", report.results().get(1).actualDecision());
    }

    @Test
    void 空用例集全过() {
        RunReport report = new PolicyTestRunner(engine).run(List.of());
        assertEquals(0, report.total());
        assertTrue(report.allPassed());
    }
}

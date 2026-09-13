package cn.chyuan.ai.domain.policy.service;

import cn.chyuan.ai.domain.policy.service.PolicyEngine.PolicyStatement;
import cn.chyuan.ai.domain.policy.service.PolicyTestRunner.PolicyCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 策略 bundle 单测（工单 0267 AH7）：往返一致/校验和防篡改/表达式预检/用例回归全或无。
 */
class PolicyBundleCodecTest {

    private List<PolicyStatement> policies;
    private List<PolicyCase> cases;

    @BeforeEach
    void setUp() {
        policies = List.of(
                new PolicyStatement(null, "allow-gpt", "*", "/^gpt/", "chat", null,
                        PolicyEngine.EFFECT_ALLOW, 10, true, null, "op"),
                new PolicyStatement(null, "deny-bronze", "*", "*", "*",
                        "env.tier == 'bronze'", PolicyEngine.EFFECT_DENY, 50, true, null, "op"));
        cases = List.of(
                new PolicyCase("allow-case", "k1", "gpt-5", "chat", Map.of("tier", "gold"),
                        "ALLOW", "allow-gpt"),
                new PolicyCase("deny-case", "k1", "gpt-5", "chat", Map.of("tier", "bronze"),
                        "DENY", "deny-bronze"));
    }

    @Test
    void 导出导入往返一致() {
        String json = PolicyBundleCodec.export("v1", policies, cases);
        PolicyBundleCodec.PolicyBundle bundle = PolicyBundleCodec.fromJson(json);
        assertEquals("v1", bundle.version());
        assertEquals(2, bundle.policies().size());
        assertEquals(2, bundle.cases().size());
        // 再导出与首导出逐字节一致（确定性）
        assertEquals(json, PolicyBundleCodec.export("v1", bundle.policies(), bundle.cases()));
        // 校验和验证通过
        PolicyBundleCodec.ImportReport report = PolicyBundleCodec.validateImport(bundle,
                this::runnerFor);
        assertTrue(report.success());
        assertEquals(2, report.passed());
    }

    @Test
    void 校验和防篡改与表达式预检() {
        String json = PolicyBundleCodec.export("v1", policies, cases);
        // 篡改内容
        String tampered = json.replace("allow-gpt", "evil-name");
        PolicyBundleCodec.ImportReport bad = PolicyBundleCodec.validateImport(
                PolicyBundleCodec.fromJson(tampered), this::runnerFor);
        assertFalse(bad.success());
        assertTrue(bad.errors().get(0).contains("校验和"));
        // 非法条件表达式（校验和重算仍通过——预检独立拦截）
        List<PolicyStatement> broken = List.of(new PolicyStatement(null, "broken", "*", "*", "*",
                "1 <", PolicyEngine.EFFECT_ALLOW, 1, true, null, "op"));
        String brokenJson = PolicyBundleCodec.export("v2", broken, List.of());
        PolicyBundleCodec.ImportReport badExpr = PolicyBundleCodec.validateImport(
                PolicyBundleCodec.fromJson(brokenJson), this::runnerFor);
        assertFalse(badExpr.success());
        assertTrue(badExpr.errors().get(0).contains("条件表达式非法"));
        // 非法结构
        assertThrows(IllegalArgumentException.class, () -> PolicyBundleCodec.fromJson("not-json"));
    }

    @Test
    void 用例失败全不落库() {
        List<PolicyCase> failingCases = List.of(
                new PolicyCase("bad-case", "k1", "gpt-5", "chat", Map.of("tier", "gold"),
                        "DENY", null));
        String json = PolicyBundleCodec.export("v3", policies, failingCases);
        PolicyBundleCodec.ImportReport report = PolicyBundleCodec.validateImport(
                PolicyBundleCodec.fromJson(json), this::runnerFor);
        assertFalse(report.success());
        assertEquals(1, report.failed());
        assertTrue(report.errors().get(0).contains("用例"));
    }

    /** 用待导入策略装配的独立引擎执行用例（不触碰现有 registry） */
    private PolicyTestRunner runnerFor(List<PolicyStatement> statements) {
        return new PolicyTestRunner(new PolicyEngine(
                new PolicyEngine.InMemoryPolicyStore(statements), new PolicyDecisionCache(16)));
    }
}

package cn.chyuan.ai.domain.policy.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 策略单元测试框架（工单 0266 AH6，借鉴 OPA policy test/Regal）—
 * 用例 = 输入三元组 + 环境 + 期望结论 + 可选期望命中策略；运行策略集逐例断言出报告。
 * 发布门联动（testGateEnabled）：注册/更新策略或导入 bundle 存在失败用例即拒绝（全或无）。
 *
 * @author chyuan
 */
@Service
public class PolicyTestRunner {

    /** 单条用例 */
    public record PolicyCase(String name, String subject, String object, String action,
            java.util.Map<String, Object> envContext, String expectedDecision,
            String expectedHitStatement) {
    }

    /** 单例结果 */
    public record CaseResult(String name, boolean passed, String expectedDecision,
            String actualDecision, List<String> actualHits, String reason) {
    }

    /** 运行报告 */
    public record RunReport(int total, int passed, int failed, List<CaseResult> results) {

        public boolean allPassed() {
            return failed == 0;
        }
    }

    private final PolicyEngine engine;

    /** 发布门开关（config.policy.test-gate.enabled，默认 false） */
    private volatile boolean testGateEnabled = false;

    public PolicyTestRunner(PolicyEngine engine) {
        this.engine = engine;
    }

    public boolean isTestGateEnabled() {
        return testGateEnabled;
    }

    /** 发布门开关（config.policy.test-gate.enabled，默认 false） */
    @Value("${policy.test-gate.enabled:false}")
    private void setTestGateEnabled(boolean enabled) {
        this.testGateEnabled = enabled;
    }

    /** 运行全部用例 */
    public RunReport run(List<PolicyCase> cases) {
        List<CaseResult> results = new ArrayList<>();
        int passed = 0;
        for (PolicyCase policyCase : cases == null ? List.<PolicyCase>of() : cases) {
            CaseResult result = runOne(policyCase);
            if (result.passed()) {
                passed++;
            }
            results.add(result);
        }
        return new RunReport(results.size(), passed, results.size() - passed, List.copyOf(results));
    }

    private CaseResult runOne(PolicyCase policyCase) {
        try {
            PolicyEngine.Decision decision = engine.evaluate(policyCase.subject(), policyCase.object(),
                    policyCase.action(), policyCase.envContext());
            boolean decisionOk = decision.decision().equals(policyCase.expectedDecision());
            boolean hitOk = policyCase.expectedHitStatement() == null
                    || policyCase.expectedHitStatement().isBlank()
                    || decision.hitStatementNames().contains(policyCase.expectedHitStatement());
            if (decisionOk && hitOk) {
                return new CaseResult(policyCase.name(), true, policyCase.expectedDecision(),
                        decision.decision(), decision.hitStatementNames(), null);
            }
            String reason = !decisionOk
                    ? "期望 " + policyCase.expectedDecision() + " 实际 " + decision.decision()
                    : "期望命中 " + policyCase.expectedHitStatement() + "，实际命中 " + decision.hitStatementNames();
            return new CaseResult(policyCase.name(), false, policyCase.expectedDecision(),
                    decision.decision(), decision.hitStatementNames(), reason);
        } catch (Exception e) {
            return new CaseResult(policyCase.name(), false, policyCase.expectedDecision(),
                    "ERROR", List.of(), "求值异常: " + e.getMessage());
        }
    }
}

package cn.chyuan.ai.domain.policy.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 策略 bundle 编解码（工单 0267 AH7，借鉴 OPA bundle 思想）—
 * bundle = 策略集 + 用例集 + 版本 + SHA-256 校验和（规范化载荷确定性键序）；
 * 导入全或无：校验和 → 表达式预检 → 用例全过才入 registry（任一失败不落库）。
 *
 * @author chyuan
 */
public final class PolicyBundleCodec {

    /** bundle 载荷 */
    public record PolicyBundle(String version, List<PolicyEngine.PolicyStatement> policies,
            List<PolicyTestRunner.PolicyCase> cases, String checksum) {
    }

    /** 导入报告 */
    public record ImportReport(int policies, int cases, int passed, int failed,
            List<String> errors) {

        public boolean success() {
            return errors.isEmpty() && failed == 0;
        }
    }

    private PolicyBundleCodec() {
    }

    /** 导出（确定性序列化 + 校验和） */
    public static String export(String version, List<PolicyEngine.PolicyStatement> policies,
            List<PolicyTestRunner.PolicyCase> cases) {
        List<PolicyEngine.PolicyStatement> sortedPolicies = policies.stream()
                .sorted(java.util.Comparator.comparing(PolicyEngine.PolicyStatement::name))
                .toList();
        List<PolicyTestRunner.PolicyCase> sortedCases = cases.stream()
                .sorted(java.util.Comparator.comparing(PolicyTestRunner.PolicyCase::name))
                .toList();
        String checksum = sha256(canonical(version, sortedPolicies, sortedCases));
        JSONObject root = new JSONObject(true);
        root.put("version", version);
        JSONArray policyArr = new JSONArray();
        for (PolicyEngine.PolicyStatement statement : sortedPolicies) {
            JSONObject item = new JSONObject(true);
            item.put("name", statement.name());
            item.put("sub", statement.subPattern());
            item.put("obj", statement.objPattern());
            item.put("act", statement.actPattern());
            item.put("condition", statement.conditionExpr());
            item.put("effect", statement.effect());
            item.put("priority", statement.priority());
            item.put("enabled", statement.enabled());
            item.put("note", statement.note());
            item.put("operator", statement.operator());
            policyArr.add(item);
        }
        root.put("policies", policyArr);
        JSONArray caseArr = new JSONArray();
        for (PolicyTestRunner.PolicyCase policyCase : sortedCases) {
            JSONObject item = new JSONObject(true);
            item.put("name", policyCase.name());
            item.put("subject", policyCase.subject());
            item.put("object", policyCase.object());
            item.put("action", policyCase.action());
            item.put("env", policyCase.envContext());
            item.put("expectedDecision", policyCase.expectedDecision());
            item.put("expectedHit", policyCase.expectedHitStatement());
            caseArr.add(item);
        }
        root.put("cases", caseArr);
        root.put("checksum", checksum);
        return root.toJSONString();
    }

    /** 反序列化（结构非法抛 IllegalArgumentException；校验和由 import 显式校验） */
    public static PolicyBundle fromJson(String json) {
        try {
            JSONObject root = JSON.parseObject(json);
            List<PolicyEngine.PolicyStatement> policies = new ArrayList<>();
            JSONArray policyArr = root.getJSONArray("policies");
            if (policyArr != null) {
                for (int i = 0; i < policyArr.size(); i++) {
                    JSONObject item = policyArr.getJSONObject(i);
                    policies.add(new PolicyEngine.PolicyStatement(null, item.getString("name"),
                            item.getString("sub"), item.getString("obj"), item.getString("act"),
                            item.getString("condition"), item.getString("effect"),
                            item.getIntValue("priority"), item.getBooleanValue("enabled"),
                            item.getString("note"), item.getString("operator")));
                }
            }
            List<PolicyTestRunner.PolicyCase> cases = new ArrayList<>();
            JSONArray caseArr = root.getJSONArray("cases");
            if (caseArr != null) {
                for (int i = 0; i < caseArr.size(); i++) {
                    JSONObject item = caseArr.getJSONObject(i);
                    cases.add(new PolicyTestRunner.PolicyCase(item.getString("name"),
                            item.getString("subject"), item.getString("object"),
                            item.getString("action"),
                            item.getJSONObject("env") == null ? java.util.Map.of()
                                    : new java.util.HashMap<>(item.getJSONObject("env")),
                            item.getString("expectedDecision"), item.getString("expectedHit")));
                }
            }
            return new PolicyBundle(root.getString("version"), List.copyOf(policies),
                    List.copyOf(cases), root.getString("checksum"));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("bundle JSON 非法: " + e.getMessage());
        }
    }

    /**
     * 导入校验（纯函数，不落库）：校验和 → 表达式预检 → 用例集回归。
     * runner 用「待导入策略临时装配的引擎」执行——由调用方构造 evaluator 函数注入。
     */
    public static ImportReport validateImport(PolicyBundle bundle,
            java.util.function.Function<List<PolicyEngine.PolicyStatement>, PolicyTestRunner> runnerFactory) {
        List<String> errors = new ArrayList<>();
        String canonical = canonical(bundle.version(), sortedPolicies(bundle.policies()),
                sortedCases(bundle.cases()));
        if (bundle.checksum() == null || !sha256(canonical).equals(bundle.checksum())) {
            return new ImportReport(bundle.policies().size(), bundle.cases().size(), 0,
                    bundle.cases().size(), List.of("校验和不匹配，bundle 已被篡改"));
        }
        for (PolicyEngine.PolicyStatement statement : bundle.policies()) {
            if (statement.conditionExpr() != null && !statement.conditionExpr().isBlank()) {
                try {
                    ExprKernel.validate(statement.conditionExpr());
                } catch (Exception e) {
                    errors.add("策略 " + statement.name() + " 条件表达式非法: " + e.getMessage());
                }
            }
        }
        if (!errors.isEmpty()) {
            return new ImportReport(bundle.policies().size(), bundle.cases().size(), 0,
                    bundle.cases().size(), List.copyOf(errors));
        }
        PolicyTestRunner runner = runnerFactory.apply(bundle.policies());
        PolicyTestRunner.RunReport report = runner.run(bundle.cases());
        List<String> failures = new ArrayList<>(errors);
        report.results().stream().filter(r -> !r.passed())
                .forEach(r -> failures.add("用例 " + r.name() + " 失败: " + r.reason()));
        return new ImportReport(bundle.policies().size(), bundle.cases().size(),
                report.passed(), report.failed(), List.copyOf(failures));
    }

    static String canonical(String version, List<PolicyEngine.PolicyStatement> policies,
            List<PolicyTestRunner.PolicyCase> cases) {
        StringBuilder sb = new StringBuilder();
        sb.append("version=").append(version == null ? "" : version).append('\n');
        for (PolicyEngine.PolicyStatement statement : sortedPolicies(policies)) {
            sb.append(statement.name()).append('#').append(statement.subPattern())
                    .append('#').append(statement.objPattern()).append('#').append(statement.actPattern())
                    .append('#').append(statement.conditionExpr() == null ? "" : statement.conditionExpr())
                    .append('#').append(statement.effect()).append('#').append(statement.priority())
                    .append('#').append(statement.enabled()).append('\n');
        }
        for (PolicyTestRunner.PolicyCase policyCase : sortedCases(cases)) {
            sb.append("case#").append(policyCase.name()).append('#').append(policyCase.subject())
                    .append('#').append(policyCase.object()).append('#').append(policyCase.action())
                    .append('#').append(policyCase.expectedDecision())
                    .append('#').append(policyCase.expectedHitStatement() == null ? "" : policyCase.expectedHitStatement())
                    .append('\n');
        }
        return sb.toString();
    }

    private static List<PolicyEngine.PolicyStatement> sortedPolicies(List<PolicyEngine.PolicyStatement> policies) {
        List<PolicyEngine.PolicyStatement> sorted = new ArrayList<>(policies);
        sorted.sort(java.util.Comparator.comparing(PolicyEngine.PolicyStatement::name));
        return sorted;
    }

    private static List<PolicyTestRunner.PolicyCase> sortedCases(List<PolicyTestRunner.PolicyCase> cases) {
        List<PolicyTestRunner.PolicyCase> sorted = new ArrayList<>(cases);
        sorted.sort(java.util.Comparator.comparing(PolicyTestRunner.PolicyCase::name));
        return sorted;
    }

    static String sha256(String data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

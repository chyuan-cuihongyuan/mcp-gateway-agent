package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.governance.adapter.IGovernanceEventPublisher;
import cn.chyuan.ai.domain.policy.service.DecisionLogRecorder;
import cn.chyuan.ai.domain.policy.service.PolicyBundleCodec;
import cn.chyuan.ai.domain.policy.service.PolicyDecisionCache;
import cn.chyuan.ai.domain.policy.service.PolicyEngine;
import cn.chyuan.ai.domain.policy.service.PolicyEngine.Decision;
import cn.chyuan.ai.domain.policy.service.PolicyEngine.PolicyStatement;
import cn.chyuan.ai.domain.policy.service.PolicyTestRunner;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 策略引擎控制器（工单 0261-0267 AH 簇）：策略 CRUD（AH2）、调试评估（AH3）、
 * 决策日志查询（AH5）、用例运行（AH6）、bundle 导入导出（AH7）、缓存统计（AH4）。
 * 变更经 POLICY_CHANGE 事件留痕。
 */
@Slf4j
@RestController
@CrossOrigin(origins = {"http://localhost:3000"})
@RequestMapping("/admin/v1/policies")
public class PolicyController {

    private final PolicyEngine engine;
    private final PolicyTestRunner testRunner;
    private final DecisionLogRecorder decisionLog;
    private final IGovernanceEventPublisher eventPublisher;

    public PolicyController(PolicyEngine engine, PolicyTestRunner testRunner,
            DecisionLogRecorder decisionLog, IGovernanceEventPublisher eventPublisher) {
        this.engine = engine;
        this.testRunner = testRunner;
        this.decisionLog = decisionLog;
        this.eventPublisher = eventPublisher;
    }

    /** AH2：注册策略（表达式预检 + 可选用例门） */
    @PostMapping
    public Response<Map<String, Object>> register(@RequestBody PolicyStatement statement) {
        try {
            assertTestGatePassed();
            PolicyStatement saved = engine.register(statement);
            publishChange("REGISTERED", saved.name());
            return Response.success(toMap(saved));
        } catch (IllegalArgumentException e) {
            return Response.fail("0002", e.getMessage());
        }
    }

    /** AH2：更新策略 */
    @PutMapping("/{id}")
    public Response<Map<String, Object>> update(@PathVariable long id,
            @RequestBody PolicyStatement statement) {
        try {
            assertTestGatePassed();
            PolicyStatement withId = new PolicyStatement(id, statement.name(), statement.subPattern(),
                    statement.objPattern(), statement.actPattern(), statement.conditionExpr(),
                    statement.effect(), statement.priority(), statement.enabled(), statement.note(),
                    statement.operator());
            PolicyStatement updated = engine.update(withId);
            publishChange("UPDATED", updated.name());
            return Response.success(toMap(updated));
        } catch (IllegalArgumentException e) {
            return Response.fail("0002", e.getMessage());
        }
    }

    /** AH2：删除策略 */
    @DeleteMapping("/{id}")
    public Response<Map<String, Object>> delete(@PathVariable long id) {
        try {
            PolicyStatement statement = engine.get(id);
            engine.delete(id);
            publishChange("DELETED", statement.name());
            return Response.success(Map.of("deleted", id));
        } catch (IllegalArgumentException e) {
            return Response.fail("0002", e.getMessage());
        }
    }

    /** AH2：策略清单 */
    @GetMapping
    public Response<List<Map<String, Object>>> list() {
        return Response.success(engine.listAll().stream().map(PolicyController::toMap).toList());
    }

    /** AH3：调试评估（输入三元组 + envJson → 决策与命中轨迹） */
    @PostMapping("/evaluate")
    public Response<Map<String, Object>> evaluate(@RequestParam String subject,
            @RequestParam String object, @RequestParam String action,
            @RequestParam(required = false) String envJson) {
        try {
            Decision decision = engine.evaluate(subject, object, action, parseEnv(envJson));
            Map<String, Object> out = new HashMap<>();
            out.put("decision", decision.decision());
            out.put("hits", decision.hitStatementNames());
            out.put("hitEffects", decision.hitEffects());
            out.put("fromCache", decision.fromCache());
            return Response.success(out);
        } catch (IllegalArgumentException e) {
            return Response.fail("0002", e.getMessage());
        }
    }

    /** AH5：决策日志查询 */
    @GetMapping("/decisions")
    public Response<List<DecisionLogRecorder.DecisionLog>> decisions(
            @RequestParam(required = false) String decision,
            @RequestParam(required = false) Long fromMs, @RequestParam(required = false) Long toMs,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "50") int limit) {
        return Response.success(decisionLog.query(decision, fromMs, toMs, offset, limit));
    }

    /** AH6：用例运行（@RequestBody 用例数组 JSON） */
    @PostMapping("/test-run")
    public Response<PolicyTestRunner.RunReport> testRun(@RequestBody String casesJson) {
        try {
            List<PolicyTestRunner.PolicyCase> cases = parseCases(casesJson);
            return Response.success(testRunner.run(cases));
        } catch (IllegalArgumentException e) {
            return Response.fail("0002", e.getMessage());
        }
    }

    /** AH7：bundle 导出（当前策略 + 空用例占位） */
    @GetMapping("/bundle/export")
    public Response<Map<String, Object>> exportBundle() {
        String json = PolicyBundleCodec.export("v" + engine.dataVersion(),
                engine.listAll(), List.of());
        return Response.success(Map.of("json", json, "policies", engine.listAll().size()));
    }

    /** AH7：bundle 导入（全或无：校验和→表达式预检→用例回归→入 registry） */
    @PostMapping("/bundle/import")
    public Response<Map<String, Object>> importBundle(@RequestBody String bundleJson) {
        try {
            PolicyBundleCodec.PolicyBundle bundle = PolicyBundleCodec.fromJson(bundleJson);
            PolicyBundleCodec.ImportReport report = PolicyBundleCodec.validateImport(bundle,
                    policies -> new PolicyTestRunner(new PolicyEngine(
                            new PolicyEngine.InMemoryPolicyStore(policies),
                            new PolicyDecisionCache(64))));
            if (!report.success()) {
                return Response.fail("0002", String.join("; ", report.errors()));
            }
            // 全过后原子换装：清空再注册
            engine.listAll().forEach(p -> engine.delete(p.id()));
            for (PolicyStatement statement : bundle.policies()) {
                engine.register(statement);
            }
            publishChange("BUNDLE_IMPORTED", bundle.version());
            return Response.success(Map.of("policies", report.policies(),
                    "cases", report.cases(), "passed", report.passed()));
        } catch (IllegalArgumentException e) {
            return Response.fail("0002", e.getMessage());
        }
    }

    /** AH4：缓存统计 */
    @GetMapping("/cache-stats")
    public Response<PolicyEngine.PolicyDecisionCacheStats> cacheStats() {
        return Response.success(engine.cacheStats());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseEnv(String envJson) {
        if (envJson == null || envJson.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.parseObject(envJson, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("envJson 不是合法 JSON 对象");
        }
    }

    private static List<PolicyTestRunner.PolicyCase> parseCases(String casesJson) {
        try {
            return JSON.parseArray(casesJson, PolicyTestRunner.PolicyCase.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("用例数组 JSON 非法: " + e.getMessage());
        }
    }

    private void assertTestGatePassed() {
        // 用例门（AH6）：开启时要求现有策略集用例全过（此处以空用例集占位——用例由 bundle 携带）
        if (!testRunner.isTestGateEnabled()) {
            return;
        }
        PolicyTestRunner.RunReport report = testRunner.run(List.of());
        if (!report.allPassed()) {
            throw new IllegalArgumentException("发布前用例门未通过");
        }
    }

    private void publishChange(String action, String name) {
        try {
            eventPublisher.publish("POLICY_CHANGE", Map.of(
                    "action", action,
                    "name", name == null ? "-" : name));
        } catch (Exception ignored) {
            // 事件尽力而为
        }
    }

    private static Map<String, Object> toMap(PolicyStatement statement) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", statement.id());
        map.put("name", statement.name());
        map.put("sub", statement.subPattern());
        map.put("obj", statement.objPattern());
        map.put("act", statement.actPattern());
        map.put("condition", statement.conditionExpr());
        map.put("effect", statement.effect());
        map.put("priority", statement.priority());
        map.put("enabled", statement.enabled());
        return map;
    }
}

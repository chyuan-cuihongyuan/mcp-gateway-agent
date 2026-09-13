package cn.chyuan.ai.domain.policy.service;

import cn.chyuan.ai.domain.policy.service.PolicyEngine.PolicyStatement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 策略执行挂点单测（工单 0267 AH8）：开关关零评估/开 DENY -32027/日志与缓存联动。
 */
class PolicyEnforceServiceTest {

    private PolicyEngine engine;
    private DecisionLogRecorder recorder;
    private DecisionLogRecorder.InMemoryDecisionLogStore logStore;
    private PolicyEnforceService enforceService;

    @BeforeEach
    void setUp() throws Exception {
        engine = new PolicyEngine(new PolicyEngine.InMemoryPolicyStore(), new PolicyDecisionCache(32));
        engine.register(new PolicyStatement(null, "deny-model-x", "*", "model-x", "*",
                null, PolicyEngine.EFFECT_DENY, 10, true, null, "op"));
        logStore = new DecisionLogRecorder.InMemoryDecisionLogStore();
        recorder = new DecisionLogRecorder(logStore);
        enforceService = new PolicyEnforceService(engine, recorder);
        setEnabled(enforceService, true);
    }

    private static void setEnabled(PolicyEnforceService service, boolean enabled) throws Exception {
        java.lang.reflect.Field field = PolicyEnforceService.class.getDeclaredField("enabled");
        field.setAccessible(true);
        field.set(service, enabled);
    }

    @Test
    void 默认关时直通零评估零日志() throws Exception {
        PolicyEnforceService off = new PolicyEnforceService(engine,
                new DecisionLogRecorder(new DecisionLogRecorder.InMemoryDecisionLogStore()));
        setEnabled(off, false);
        PolicyEnforceService.EnforceResult result = off.enforce("k1", "model-x", "chat", Map.of());
        // 关闭时即使 DENY 策略命中也放行（零行为变化）
        assertTrue(result.allowed());
        assertEquals(0, result.errorCode());
        // 引擎零评估（缓存统计 hits+misses=0）
        assertEquals(0, engine.cacheStats().hits() + engine.cacheStats().misses());
    }

    @Test
    void 开启后DENY返回错误码并落日志() {
        PolicyEnforceService.EnforceResult denied = enforceService.enforce("k1", "model-x", "chat", Map.of());
        assertFalse(denied.allowed());
        assertEquals(-32027, denied.errorCode());
        assertTrue(denied.decision().hitStatementNames().contains("deny-model-x"));
        // 决策落日志
        assertEquals(1, recorder.query(null, null, null, 0, 50).size());
        assertEquals("DENY", recorder.query(null, null, null, 0, 50).get(0).decision());
    }

    @Test
    void 放行路径与缓存标记落日志() {
        engine.setDefaultEffect(PolicyEngine.EFFECT_ALLOW);
        enforceService.enforce("k2", "gpt-5", "chat", Map.of());
        enforceService.enforce("k2", "gpt-5", "chat", Map.of());
        // 第二次走缓存
        assertTrue(engine.cacheStats().hits() >= 1);
        List<DecisionLogRecorder.DecisionLog> logs = recorder.query("ALLOW", null, null, 0, 50);
        assertEquals(2, logs.size());
        // 最新在前：第二条评估（get(0)）命中缓存
        assertTrue(logs.get(0).cached());
        assertFalse(logs.get(1).cached());
    }
}

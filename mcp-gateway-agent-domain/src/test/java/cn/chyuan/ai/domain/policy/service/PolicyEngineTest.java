package cn.chyuan.ai.domain.policy.service;

import cn.chyuan.ai.domain.policy.service.PolicyEngine.Decision;
import cn.chyuan.ai.domain.policy.service.PolicyEngine.PolicyStatement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 策略引擎单测（工单 0261/0262/0263 AH2/AH3）：注册表预检/三态匹配/deny-overrides/缓存失效。
 */
class PolicyEngineTest {

    private PolicyEngine engine;

    @BeforeEach
    void setUp() {
        engine = new PolicyEngine(new PolicyEngine.InMemoryPolicyStore(), new PolicyDecisionCache(64));
    }

    private PolicyStatement statement(String name, String sub, String obj, String act,
            String condition, String effect, int priority) {
        return new PolicyStatement(null, name, sub, obj, act, condition, effect, priority, true, null, "op");
    }

    @Test
    void 注册表与表达式预检() {
        PolicyStatement saved = engine.register(statement("allow-gpt", "key-*", "/^gpt/", "chat",
                null, PolicyEngine.EFFECT_ALLOW, 10));
        assertEquals(1L, saved.id());
        assertTrue(engine.get(saved.id()).enabled());
        // 非法条件表达式拒绝注册
        assertThrows(IllegalArgumentException.class,
                () -> engine.register(statement("bad", "*", "*", "*", "1 <", PolicyEngine.EFFECT_ALLOW, 0)));
        // 非法 effect 拒绝
        assertThrows(IllegalArgumentException.class,
                () -> new PolicyStatement(null, "x", "*", "*", "*", null, "MAYBE", 0, true, null, "op"));
        // 更新与删除
        engine.update(new PolicyStatement(saved.id(), "allow-gpt", "key-*", "*", "chat",
                null, PolicyEngine.EFFECT_ALLOW, 20, false, null, "op"));
        assertFalse(engine.get(saved.id()).enabled());
        engine.delete(saved.id());
        assertThrows(IllegalArgumentException.class, () -> engine.get(saved.id()));
    }

    @Test
    void 三态模式匹配() {
        assertTrue(PolicyEngine.patternMatch("*", "anything"));
        assertTrue(PolicyEngine.patternMatch("key-*", "key-123"));
        assertFalse(PolicyEngine.patternMatch("key-*", "other-123"));
        assertTrue(PolicyEngine.patternMatch("/^gpt/", "gpt-5-turbo"));
        assertFalse(PolicyEngine.patternMatch("/^gpt/", "claude-3"));
        assertTrue(PolicyEngine.patternMatch("chat", "chat"));
        assertFalse(PolicyEngine.patternMatch("chat", "chatx"));
    }

    @Test
    void denyOverrides压制高优先级ALLOW() {
        engine.register(statement("allow-all", "*", "*", "*", null, PolicyEngine.EFFECT_ALLOW, 100));
        engine.register(statement("deny-model-x", "*", "model-x", "*", null, PolicyEngine.EFFECT_DENY, 1));
        // 高优先级 ALLOW 命中但 DENY 压制
        Decision denied = engine.evaluate("k1", "model-x", "chat", Map.of());
        assertFalse(denied.allowed());
        assertEquals(2, denied.hitStatementNames().size());
        // 无 DENY 时取最高优先级 ALLOW
        Decision allowed = engine.evaluate("k1", "gpt-5", "chat", Map.of());
        assertTrue(allowed.allowed());
        // defaultEffect 仅在无命中时生效：allow-all 通配命中下仍 ALLOW（defaultEffect 不压制实际命中）
        engine.setDefaultEffect(PolicyEngine.EFFECT_DENY);
        Decision matched = engine.evaluate("k1", "unknown", "chat", Map.of());
        assertTrue(matched.allowed());
        assertEquals(1, matched.hitStatementNames().size());
    }

    @Test
    void 条件表达式与环境上下文() {
        engine.register(statement("gold-only", "*", "*", "*",
                "env.tier == 'gold' && env.qps >= 100", PolicyEngine.EFFECT_ALLOW, 1));
        assertTrue(engine.evaluate("k1", "m", "chat",
                Map.of("tier", "gold", "qps", 150)).allowed());
        // 条件不满足 → defaultEffect
        assertFalse(engine.evaluate("k1", "m", "chat",
                Map.of("tier", "bronze", "qps", 10)).allowed());
        // subject/object/action 路径也可用
        engine.register(statement("path-cond", "*", "*", "*",
                "subject.startsWith", PolicyEngine.EFFECT_ALLOW, 1));
        // startsWith 不是合法求值（路径无界）→ 条件不成立 → 不命中（空上下文避免 Map.of 禁 null）
        assertFalse(engine.evaluate("k1", "m", "chat", Map.of()).allowed());
    }

    @Test
    void 缓存命中与版本失效() {
        engine.register(statement("allow-all", "*", "*", "*", null, PolicyEngine.EFFECT_ALLOW, 1));
        Decision first = engine.evaluate("k1", "m", "chat", Map.of());
        assertFalse(first.fromCache());
        Decision second = engine.evaluate("k1", "m", "chat", Map.of());
        assertTrue(second.fromCache());
        // 策略变更 → 版本失效 → 回源
        engine.register(statement("deny-m", "*", "m", "*", null, PolicyEngine.EFFECT_DENY, 50));
        Decision afterChange = engine.evaluate("k1", "m", "chat", Map.of());
        assertFalse(afterChange.fromCache());
        assertFalse(afterChange.allowed());
        // 统计
        assertEquals(1, engine.cacheStats().invalidations());
        assertTrue(engine.cacheStats().misses() >= 2);
    }
}

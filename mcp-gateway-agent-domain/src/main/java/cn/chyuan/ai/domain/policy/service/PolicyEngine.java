package cn.chyuan.ai.domain.policy.service;

import cn.chyuan.ai.domain.policy.service.PolicyDecisionCache.PolicyDecisionCacheStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 策略引擎（工单 0261-0263 AH2/AH3/AH4，借鉴 Casbin PERM/OPA 决策思想）—
 * 策略文档 = (sub/obj/act 模式 + 条件表达式 + effect + priority + enabled)；
 * 匹配器链支持 exact/通配 *（正则转译）/regex（/.../ 包裹）三态 + ExprKernel 条件；
 * 合并语义 deny-overrides：任一 DENY 命中即 DENY，否则取最高优先级 ALLOW，
 * 无命中取策略集 defaultEffect（默认 DENY fail-closed）。决策缓存 LRU + 版本失效。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class PolicyEngine {

    public static final String EFFECT_ALLOW = "ALLOW";
    public static final String EFFECT_DENY = "DENY";
    private static final Set<String> LEGAL_EFFECTS = Set.of(EFFECT_ALLOW, EFFECT_DENY);

    /** 策略语句值对象 */
    public record PolicyStatement(Long id, String name, String subPattern, String objPattern,
            String actPattern, String conditionExpr, String effect, int priority,
            boolean enabled, String note, String operator) {

        public PolicyStatement {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("策略名不能为空");
            }
            if (subPattern == null || objPattern == null || actPattern == null) {
                throw new IllegalArgumentException("sub/obj/act 模式不能为空（通配用 *）");
            }
            if (effect == null || !LEGAL_EFFECTS.contains(effect)) {
                throw new IllegalArgumentException("非法 effect: " + effect);
            }
        }
    }

    /** 决策结果（含命中轨迹供决策日志用） */
    public record Decision(String decision, List<String> hitStatementNames,
            List<String> hitEffects, boolean fromCache) {

        public boolean allowed() {
            return EFFECT_ALLOW.equals(decision);
        }
    }

    /** 策略持久化端口（infrastructure 经 MyBatis 落 policy_definition 表） */
    public interface PolicyStore {

        void insert(PolicyStatement statement);

        void update(PolicyStatement statement);

        void deleteById(long id);

        PolicyStatement findById(long id);

        List<PolicyStatement> listAll();
    }

    private final PolicyStore store;
    private final PolicyDecisionCache cache;
    /** 数据版本（任何变更递增，缓存全量失效依据） */
    private final AtomicLong dataVersion = new AtomicLong();
    /** 无命中默认结论（fail-closed；可由 bundle 覆盖为 ALLOW 的场景由调用方显式传参） */
    private volatile String defaultEffect = EFFECT_DENY;

    public PolicyEngine(PolicyStore store, PolicyDecisionCache cache) {
        this.store = store;
        this.cache = cache;
    }

    public String defaultEffect() {
        return defaultEffect;
    }

    public void setDefaultEffect(String effect) {
        if (!LEGAL_EFFECTS.contains(effect)) {
            throw new IllegalArgumentException("非法 effect: " + effect);
        }
        this.defaultEffect = effect;
        bumpVersion();
    }

    public long dataVersion() {
        return dataVersion.get();
    }

    private void bumpVersion() {
        dataVersion.incrementAndGet();
        cache.invalidate();
    }

    // ── AH2 注册表 ──
    public PolicyStatement register(PolicyStatement statement) {
        if (statement.conditionExpr() != null && !statement.conditionExpr().isBlank()) {
            ExprKernel.validate(statement.conditionExpr());
        }
        PolicyStatement withId = new PolicyStatement(nextId(), statement.name(), statement.subPattern(),
                statement.objPattern(), statement.actPattern(), statement.conditionExpr(),
                statement.effect(), statement.priority(), statement.enabled(), statement.note(),
                statement.operator());
        store.insert(withId);
        bumpVersion();
        log.info("策略注册: id={} name={} effect={} priority={}", withId.id(), withId.name(),
                withId.effect(), withId.priority());
        return withId;
    }

    public PolicyStatement update(PolicyStatement statement) {
        require(statement.id());
        if (statement.conditionExpr() != null && !statement.conditionExpr().isBlank()) {
            ExprKernel.validate(statement.conditionExpr());
        }
        store.update(statement);
        bumpVersion();
        return statement;
    }

    public void delete(long id) {
        require(id);
        store.deleteById(id);
        bumpVersion();
    }

    public PolicyStatement get(long id) {
        return require(id);
    }

    public List<PolicyStatement> listAll() {
        return store.listAll();
    }

    /** 启用策略集（按优先级倒序：高优先级先评估） */
    public List<PolicyStatement> enabledStatements() {
        return store.listAll().stream()
                .filter(PolicyStatement::enabled)
                .sorted(Comparator.comparingInt(PolicyStatement::priority).reversed())
                .toList();
    }

    // ── AH3 匹配与合并 ──
    /**
     * 决策：sub/obj/act + 环境上下文 → ALLOW|DENY（deny-overrides + 最高优先级 ALLOW）。
     * envContext 作为条件表达式求值根变量的一部分（键 env 下）。
     */
    public Decision evaluate(String subject, String object, String action,
            Map<String, Object> envContext) {
        String fingerprint = PolicyDecisionCache.fingerprint(subject, object, action, envContext,
                dataVersion.get());
        PolicyDecisionCache.CachedDecision cached = cache.get(fingerprint);
        if (cached != null) {
            return new Decision(cached.decision(), cached.hitStatementNames(), cached.hitEffects(), true);
        }
        List<String> hitNames = new ArrayList<>();
        List<String> hitEffects = new ArrayList<>();
        String bestAllow = null;
        int bestAllowPriority = Integer.MIN_VALUE;
        for (PolicyStatement statement : enabledStatements()) {
            if (!matches(statement, subject, object, action, envContext)) {
                continue;
            }
            hitNames.add(statement.name());
            hitEffects.add(statement.effect());
            if (EFFECT_DENY.equals(statement.effect())) {
                cache.put(fingerprint, new PolicyDecisionCache.CachedDecision(EFFECT_DENY,
                        List.copyOf(hitNames), List.copyOf(hitEffects)));
                return new Decision(EFFECT_DENY, List.copyOf(hitNames), List.copyOf(hitEffects), false);
            }
            if (statement.priority() > bestAllowPriority) {
                bestAllow = EFFECT_ALLOW;
                bestAllowPriority = statement.priority();
            }
        }
        String decision = bestAllow != null ? bestAllow : defaultEffect;
        cache.put(fingerprint, new PolicyDecisionCache.CachedDecision(decision,
                List.copyOf(hitNames), List.copyOf(hitEffects)));
        return new Decision(decision, List.copyOf(hitNames), List.copyOf(hitEffects), false);
    }

    /** 单语句匹配：三态模式 × 3 维 + 可选条件表达式 */
    boolean matches(PolicyStatement statement, String subject, String object, String action,
            Map<String, Object> envContext) {
        if (!patternMatch(statement.subPattern(), subject)
                || !patternMatch(statement.objPattern(), object)
                || !patternMatch(statement.actPattern(), action)) {
            return false;
        }
        String condition = statement.conditionExpr();
        if (condition == null || condition.isBlank()) {
            return true;
        }
        Map<String, Object> root = new ConcurrentHashMap<>();
        root.put("subject", subject);
        root.put("object", object);
        root.put("action", action);
        if (envContext != null) {
            root.put("env", envContext);
        }
        try {
            return ExprKernel.evaluateBoolean(condition, root);
        } catch (Exception e) {
            // 条件求值异常 = 该语句不命中（授权 fail-closed 由 defaultEffect 兜底）
            log.warn("策略条件求值异常（视为不命中）: name={} expr={} err={}", statement.name(), condition, e.getMessage());
            return false;
        }
    }

    /** 三态模式匹配：null/* 通配；/.../ 正则（find 语义）；其余精确 */
    static boolean patternMatch(String pattern, String value) {
        if (pattern == null || pattern.isBlank() || "*".equals(pattern)) {
            return true;
        }
        if (pattern.length() >= 2 && pattern.startsWith("/") && pattern.endsWith("/")) {
            return Pattern.compile(pattern.substring(1, pattern.length() - 1))
                    .matcher(value == null ? "" : value).find();
        }
        if (pattern.contains("*")) {
            String regex = Pattern.quote(pattern).replaceAll("\\*",
                    "\\\\E.*\\\\Q");
            return Pattern.compile(regex).matcher(value == null ? "" : value).matches();
        }
        return pattern.equals(value);
    }

    private long sequence = 0;

    private synchronized long nextId() {
        long maxStored = store.listAll().stream()
                .mapToLong(s -> s.id() == null ? 0 : s.id()).max().orElse(0);
        return Math.max(maxStored, sequence) + 1;
    }

    private PolicyStatement require(Long id) {
        PolicyStatement statement = id == null ? null : store.findById(id);
        if (statement == null) {
            throw new IllegalArgumentException("策略不存在: " + id);
        }
        return statement;
    }

    /** 缓存统计透出 */
    public PolicyDecisionCacheStats cacheStats() {
        return cache.stats();
    }

    /** 内存策略存储（测试与 bundle 试算用；预置初始策略集） */
    public static class InMemoryPolicyStore implements PolicyStore {

        private final Map<Long, PolicyStatement> rows = new ConcurrentHashMap<>();
        private final AtomicLong ids = new AtomicLong();

        public InMemoryPolicyStore() {
        }

        public InMemoryPolicyStore(List<PolicyStatement> initial) {
            if (initial != null) {
                initial.forEach(statement -> {
                    long id = ids.incrementAndGet();
                    rows.put(id, new PolicyStatement(id, statement.name(), statement.subPattern(),
                            statement.objPattern(), statement.actPattern(), statement.conditionExpr(),
                            statement.effect(), statement.priority(), statement.enabled(),
                            statement.note(), statement.operator()));
                });
            }
        }

        @Override
        public void insert(PolicyStatement statement) {
            long id = statement.id() == null ? ids.incrementAndGet() : statement.id();
            ids.accumulateAndGet(id, Math::max);
            rows.put(id, statement);
        }

        @Override
        public void update(PolicyStatement statement) {
            rows.put(statement.id(), statement);
        }

        @Override
        public void deleteById(long id) {
            rows.remove(id);
        }

        @Override
        public PolicyStatement findById(long id) {
            return rows.get(id);
        }

        @Override
        public List<PolicyStatement> listAll() {
            return List.copyOf(rows.values());
        }
    }
}

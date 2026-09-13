package cn.chyuan.ai.domain.llmcache.service;

import cn.chyuan.ai.domain.llmcache.service.PrefixKeyCalculator.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 前缀缓存拦截器（工单 0280 AJ4 + 0283 AJ7 + 0284 AJ8）—
 * chat 请求前计算前缀键（AJ1）→ 基数树最长匹配（AJ2）：全深度命中取缓存快照直接返回，
 * 部分匹配记复用收益（上游 KV 复用代理指标）；未命中放行由调用方回写。
 * `prefix.cache.enabled` + 租户白名单默认关零行为变化；流式与 cache.no-cache 不缓存。
 * 命中标记沿 0097 先例走台账 CACHE_HIT + 指标，另留 ThreadLocal 标记供控制器扩展。
 */
@Slf4j
@Service
public class PrefixCacheInterceptor {

    /** 命中结果 */
    public record PrefixHit(String response, int matchedDepth, int totalBlocks, long savedTokens) {
    }

    private final PrefixCacheStore store;
    private final PrefixRadixTree radixTree = new PrefixRadixTree();
    private final CacheMetricsCollector metrics;

    @Value("${prefix.cache.enabled:false}")
    private boolean enabled;

    @Value("${prefix.cache.tenants:}")
    private String tenantWhitelist;

    @Value("${prefix.cache.ttl-ms:600000}")
    private long ttlMs;

    /** 命中标记（chat 响应链路可读；0=无，>0=命中深度） */
    private static final ThreadLocal<Integer> LAST_PREFIX_DEPTH = new ThreadLocal<>();

    public PrefixCacheInterceptor(PrefixCacheStore store, CacheMetricsCollector metrics) {
        this.store = store;
        this.metrics = metrics;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 本请求的前缀命中标记（响应链路读取后自动清除） */
    public static int consumeLastHitDepth() {
        Integer depth = LAST_PREFIX_DEPTH.get();
        LAST_PREFIX_DEPTH.remove();
        return depth == null ? 0 : depth;
    }

    /**
     * 读缓存：开关关/租户不在白名单/空消息/流式标记 → null（零开销直通）。
     * 全深度匹配且存储命中 → PrefixHit；部分匹配 → 记复用收益后返回 null。
     */
    public PrefixHit lookup(String tenant, String model, List<ChatMessage> messages, boolean stream) {
        LAST_PREFIX_DEPTH.remove();
        if (!enabled || stream || messages == null || messages.isEmpty() || !tenantAllowed(tenant)) {
            return null;
        }
        String namespace = PrefixKeyCalculator.namespace(model, tenant);
        List<String> keys = PrefixKeyCalculator.prefixKeys(namespace, messages);
        int matched = radixTree.longestMatch(keys);
        if (matched < keys.size()) {
            // 部分匹配：上游 KV 复用代理收益，不返回缓存
            metrics.recordPartial(savedTokensOf(matched));
            return null;
        }
        PrefixCacheStore.CacheEntry entry = store.get(PrefixKeyCalculator.fullKey(namespace, keys));
        if (entry == null) {
            metrics.recordMiss();
            return null;
        }
        metrics.recordHit(savedTokensOf(matched));
        LAST_PREFIX_DEPTH.set(matched);
        log.info("前缀缓存命中: tenant={} model={} depth={}/{} blocks", tenant, model, matched, keys.size());
        return new PrefixHit(entry.response(), matched, keys.size(), savedTokensOf(matched));
    }

    /** 写缓存（调用方在取得最终响应后调用；流式/no-cache 场景勿调） */
    public boolean store(String tenant, String model, List<ChatMessage> messages, String response) {
        if (!enabled || messages == null || messages.isEmpty() || !tenantAllowed(tenant)) {
            return false;
        }
        String namespace = PrefixKeyCalculator.namespace(model, tenant);
        List<String> keys = PrefixKeyCalculator.prefixKeys(namespace, messages);
        if (!radixTree.insert(keys)) {
            return false;
        }
        boolean stored = store.put(PrefixKeyCalculator.fullKey(namespace, keys), tenant, response, ttlMs);
        if (stored) {
            metrics.recordStored();
        }
        return stored;
    }

    /** 管理端点：树快照 */
    public Map<String, Object> treeSnapshot() {
        return radixTree.snapshot();
    }

    /** 管理端点：按租户清空 */
    public int clearTenant(String tenant) {
        // 进程内树共享：剪枝粒度到租户命名空间末端键代价高，管理动作直接全量失效（保守口径，审计留痕）
        return store.invalidateAll();
    }

    /** 管理端点：全量清空 */
    public int clearAll() {
        return store.invalidateAll();
    }

    private long savedTokensOf(int matchedBlocks) {
        return (long) matchedBlocks * PrefixRadixTree.DEFAULT_TOKENS_PER_BLOCK;
    }

    private boolean tenantAllowed(String tenant) {
        Set<String> whitelist = whitelist();
        return whitelist.isEmpty() || whitelist.contains(tenant);
    }

    private Set<String> whitelist() {
        if (tenantWhitelist == null || tenantWhitelist.isBlank()) {
            return Set.of();
        }
        return Set.of(tenantWhitelist.split(","));
    }
}

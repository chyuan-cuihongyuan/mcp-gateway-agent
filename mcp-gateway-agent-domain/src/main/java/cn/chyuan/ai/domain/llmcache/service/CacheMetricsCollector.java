package cn.chyuan.ai.domain.llmcache.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 前缀缓存指标（工单 0283 AJ7，Micrometer 命名沿用先例）—
 * prefix_cache_request_total{result=hit|miss|partial}、prefix_cache_saved_tokens、
 * prefix_cache_stored_total；租户维度低基数标签白名单（未知租户归 other）；
 * 内存计数器兜底聚合（无 Prometheus 环境自检端点可读）。
 */
@Component
public class CacheMetricsCollector {

    /** 租户标签白名单上限（超出归 other，防标签爆炸） */
    static final int MAX_TENANT_TAGS = 32;

    private final ObjectProvider<MeterRegistry> registryProvider;
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder partials = new LongAdder();
    private final LongAdder stored = new LongAdder();
    private final LongAdder savedTokens = new LongAdder();
    private final Map<String, LongAdder> tenantHits = new ConcurrentHashMap<>();

    public CacheMetricsCollector(ObjectProvider<MeterRegistry> registryProvider) {
        this.registryProvider = registryProvider;
    }

    /** 命中（记录节省 token 数） */
    public void recordHit(long savedTokens) {
        hits.increment();
        this.savedTokens.add(savedTokens);
        registerCounters();
    }

    public void recordMiss() {
        misses.increment();
        registerCounters();
    }

    /** 部分匹配（上游复用代理收益） */
    public void recordPartial(long savedTokens) {
        partials.increment();
        this.savedTokens.add(savedTokens);
        registerCounters();
    }

    public void recordStored() {
        stored.increment();
        registerCounters();
    }

    /** 租户维度命中（低基数白名单） */
    public void recordTenantHit(String tenant) {
        String tag = tenant == null || tenant.isBlank() ? "other" : tenant;
        tenantHits.computeIfAbsent(tag, key -> {
            if (tenantHits.size() >= MAX_TENANT_TAGS) {
                return new LongAdder(); // 超限不再新增标签维度，仅内存累计丢弃
            }
            return new LongAdder();
        }).increment();
    }

    /** 内存聚合快照（自检端点用） */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("hits", hits.sum());
        out.put("misses", misses.sum());
        out.put("partials", partials.sum());
        out.put("stored", stored.sum());
        out.put("savedTokens", savedTokens.sum());
        return out;
    }

    /** 惰性注册 Micrometer 计数器（registry 缺省时静默跳过） */
    private volatile boolean registered = false;
    private final AtomicLong gaugeSavedTokens = new AtomicLong();

    private void registerCounters() {
        if (registered) {
            gaugeSavedTokens.set(savedTokens.sum());
            return;
        }
        MeterRegistry registry = registryProvider == null ? null : registryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        registered = true;
        registry.counter("prefix_cache_request_total", "result", "hit");
        registry.counter("prefix_cache_request_total", "result", "miss");
        registry.counter("prefix_cache_request_total", "result", "partial");
        registry.counter("prefix_cache_stored_total");
        registry.gauge("prefix_cache_saved_tokens", gaugeSavedTokens, AtomicLong::doubleValue);
    }
}

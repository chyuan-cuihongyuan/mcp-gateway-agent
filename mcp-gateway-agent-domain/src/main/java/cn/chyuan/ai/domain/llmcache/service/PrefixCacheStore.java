package cn.chyuan.ai.domain.llmcache.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 前缀缓存存储（工单 0279 AJ3）—
 * 进程内：条目 = 响应快照；TTL 过期（Clock 端口注入）+ 全局容量 LRU 淘汰 +
 * 租户配额比例上限（单租户最多占容量百分比）；命中需键存在且未过期。
 * 仅幂等可缓存判定（流式不缓存/显式 no-cache 跳过）由拦截器负责。
 *
 * @author chyuan
 */
@Component
public class PrefixCacheStore {

    /** 时钟端口（测试注入） */
    public interface Clock {

        long nowMs();
    }

    /** 缓存条目 */
    public record CacheEntry(String response, long expiresAtMs, String tenant) {
    }

    public static final int DEFAULT_CAPACITY = 2_048;
    /** 单租户默认配额占比（百分比） */
    public static final int DEFAULT_TENANT_QUOTA_PERCENT = 50;

    private final int capacity;
    private final int tenantQuotaPercent;
    private final Clock clock;
    private final LinkedHashMap<String, CacheEntry> entries;
    private final Map<String, Integer> tenantCounts = new java.util.HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private long hits;
    private long misses;
    private long evictions;
    private long expirations;

    public PrefixCacheStore(@Value("${prefix.cache.capacity:" + DEFAULT_CAPACITY + "}") int capacity,
            @Value("${prefix.cache.tenant-quota-percent:" + DEFAULT_TENANT_QUOTA_PERCENT + "}") int tenantQuotaPercent,
            Clock clock) {
        this.capacity = Math.max(1, capacity);
        this.tenantQuotaPercent = Math.min(100, Math.max(1, tenantQuotaPercent));
        this.clock = clock;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                if (size() <= capacity) {
                    return false;
                }
                evictions++;
                String tenant = eldest.getValue().tenant();
                tenantCounts.merge(tenant, -1, Integer::sum);
                return true;
            }
        };
    }

    /** 写入（容量满按 LRU 淘汰；租户配额满时拒绝写入返回 false） */
    public boolean put(String key, String tenant, String response, long ttlMs) {
        lock.lock();
        try {
            long now = clock.nowMs();
            evictExpired(now);
            int tenantQuota = Math.max(1, capacity * tenantQuotaPercent / 100);
            if (tenantCounts.getOrDefault(tenant, 0) >= tenantQuota
                    && !entries.containsKey(key)) {
                return false;
            }
            CacheEntry previous = entries.remove(key);
            if (previous != null) {
                tenantCounts.merge(tenant, -1, Integer::sum);
            }
            entries.put(key, new CacheEntry(response, now + ttlMs, tenant));
            tenantCounts.merge(tenant, 1, Integer::sum);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** 读取（null=未命中：不存在或已过期） */
    public CacheEntry get(String key) {
        lock.lock();
        try {
            long now = clock.nowMs();
            CacheEntry entry = entries.get(key);
            if (entry == null) {
                misses++;
                return null;
            }
            if (entry.expiresAtMs() <= now) {
                entries.remove(key);
                tenantCounts.merge(entry.tenant(), -1, Integer::sum);
                expirations++;
                misses++;
                return null;
            }
            hits++;
            return entry;
        } finally {
            lock.unlock();
        }
    }

    /** 按租户失效（管理端点用），返回清除条数 */
    public int invalidateTenant(String tenant) {
        lock.lock();
        try {
            int before = entries.size();
            entries.entrySet().removeIf(entry -> entry.getValue().tenant().equals(tenant));
            int removed = before - entries.size();
            tenantCounts.put(tenant, 0);
            return removed;
        } finally {
            lock.unlock();
        }
    }

    /** 全量失效 */
    public int invalidateAll() {
        lock.lock();
        try {
            int removed = entries.size();
            entries.clear();
            tenantCounts.clear();
            return removed;
        } finally {
            lock.unlock();
        }
    }

    private void evictExpired(long now) {
        entries.values().removeIf(entry -> {
            if (entry.expiresAtMs() <= now) {
                expirations++;
                tenantCounts.merge(entry.tenant(), -1, Integer::sum);
                return true;
            }
            return false;
        });
    }

    /** 统计快照（容量占用/租户分布） */
    public synchronized Map<String, Object> stats() {
        lock.lock();
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("size", entries.size());
            out.put("capacity", capacity);
            out.put("hits", hits);
            out.put("misses", misses);
            out.put("evictions", evictions);
            out.put("expirations", expirations);
            out.put("tenantDistribution", new java.util.TreeMap<>(tenantCounts));
            return out;
        } finally {
            lock.unlock();
        }
    }
}

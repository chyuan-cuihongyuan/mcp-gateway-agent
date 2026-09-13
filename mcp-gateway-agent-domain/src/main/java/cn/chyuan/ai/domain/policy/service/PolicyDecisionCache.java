package cn.chyuan.ai.domain.policy.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 策略决策缓存（工单 0263 AH4）：指纹 = 决策输入归一化 + 策略数据版本；
 * LRU 容量淘汰；策略变更（版本递增）全量失效。仅缓存确定性结论。
 *
 * @author chyuan
 */
@Component
public class PolicyDecisionCache {

    /** 默认容量 */
    public static final int DEFAULT_CAPACITY = 1024;

    /** 缓存条目（命中轨迹随决策一并缓存） */
    public record CachedDecision(String decision, List<String> hitStatementNames, List<String> hitEffects) {
    }

    /** 缓存统计 */
    public record PolicyDecisionCacheStats(long hits, long misses, long invalidations, int size) {
    }

    private final int capacity;
    private final LinkedHashMap<String, CachedDecision> map;
    private final ReentrantLock lock = new ReentrantLock();
    private long hits;
    private long misses;
    private long invalidations;

    public PolicyDecisionCache() {
        this(DEFAULT_CAPACITY);
    }

    public PolicyDecisionCache(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedDecision> eldest) {
                return size() > PolicyDecisionCache.this.capacity;
            }
        };
    }

    /** 输入指纹：三元组 + 环境摘要 + 数据版本（键序归一） */
    public static String fingerprint(String subject, String object, String action,
            Map<String, Object> envContext, long dataVersion) {
        StringBuilder sb = new StringBuilder();
        sb.append(dataVersion).append('|')
                .append(subject).append('|').append(object).append('|').append(action).append('|');
        if (envContext != null && !envContext.isEmpty()) {
            envContext.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> sb.append(entry.getKey()).append('=')
                            .append(entry.getValue()).append(';'));
        }
        return sb.toString();
    }

    public CachedDecision get(String fingerprint) {
        lock.lock();
        try {
            CachedDecision cached = map.get(fingerprint);
            if (cached != null) {
                hits++;
            } else {
                misses++;
            }
            return cached;
        } finally {
            lock.unlock();
        }
    }

    public void put(String fingerprint, CachedDecision decision) {
        lock.lock();
        try {
            map.put(fingerprint, decision);
        } finally {
            lock.unlock();
        }
    }

    /** 策略变更全量失效 */
    public void invalidate() {
        lock.lock();
        try {
            if (!map.isEmpty()) {
                invalidations++;
            }
            map.clear();
        } finally {
            lock.unlock();
        }
    }

    public PolicyDecisionCacheStats stats() {
        lock.lock();
        try {
            return new PolicyDecisionCacheStats(hits, misses, invalidations, map.size());
        } finally {
            lock.unlock();
        }
    }
}

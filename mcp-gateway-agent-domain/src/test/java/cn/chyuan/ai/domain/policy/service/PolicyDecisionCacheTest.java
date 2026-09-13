package cn.chyuan.ai.domain.policy.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 决策缓存单测（工单 0263 AH4）：LRU 淘汰/失效/指纹归一/统计。
 */
class PolicyDecisionCacheTest {

    private PolicyDecisionCache.CachedDecision entry(String decision) {
        return new PolicyDecisionCache.CachedDecision(decision, List.of("hit"), List.of(decision));
    }

    @Test
    void 存取与LRU淘汰() {
        PolicyDecisionCache cache = new PolicyDecisionCache(2);
        cache.put("a", entry("ALLOW"));
        cache.put("b", entry("ALLOW"));
        assertEquals("ALLOW", cache.get("a").decision());
        cache.put("c", entry("DENY"));
        // 容量 2：b 最久未访问被淘汰，a 因 get 刷新保留
        assertNull(cache.get("b"));
        assertEquals("ALLOW", cache.get("a").decision());
        assertEquals("DENY", cache.get("c").decision());
    }

    @Test
    void 指纹归一化键序无关且版本敏感() {
        String f1 = PolicyDecisionCache.fingerprint("k", "m", "chat",
                Map.of("tier", "gold", "qps", 1), 7L);
        String f2 = PolicyDecisionCache.fingerprint("k", "m", "chat",
                new java.util.TreeMap<>(Map.of("qps", 1, "tier", "gold")), 7L);
        assertEquals(f1, f2);
        String f3 = PolicyDecisionCache.fingerprint("k", "m", "chat",
                Map.of("tier", "bronze", "qps", 1), 7L);
        assertFalse(f1.equals(f3));
        String f4 = PolicyDecisionCache.fingerprint("k", "m", "chat",
                Map.of("tier", "gold", "qps", 1), 8L);
        assertFalse(f1.equals(f4));
    }

    @Test
    void 失效与统计() {
        PolicyDecisionCache cache = new PolicyDecisionCache(8);
        cache.put("a", entry("ALLOW"));
        cache.get("a");
        cache.get("missing");
        cache.invalidate();
        assertNull(cache.get("a"));
        PolicyDecisionCache.PolicyDecisionCacheStats stats = cache.stats();
        assertEquals(1, stats.hits());
        assertEquals(2, stats.misses());
        assertEquals(1, stats.invalidations());
        assertEquals(0, stats.size());
        // 空缓存 invalidate 不计次
        cache.invalidate();
        assertEquals(1, cache.stats().invalidations());
    }
}

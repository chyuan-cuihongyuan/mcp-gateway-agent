package cn.chyuan.ai.domain.llmcache.service;

import cn.chyuan.ai.domain.llmcache.service.PrefixCacheStore.CacheEntry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 前缀缓存存储单测（工单 0279 AJ3）：TTL 过期/LRU 淘汰/租户隔离与配额/清空。
 */
class PrefixCacheStoreTest {

    private PrefixCacheStore store(long nowMs) {
        AtomicLong clock = new AtomicLong(nowMs);
        return new PrefixCacheStore(4, 50, clock::get);
    }

    @Test
    void 读写与TTL过期() {
        AtomicLong now = new AtomicLong(1_000);
        PrefixCacheStore store = new PrefixCacheStore(4, 50, now::get);
        assertTrue(store.put("k1", "t1", "resp-1", 500));
        CacheEntry entry = store.get("k1");
        assertEquals("resp-1", entry.response());
        // 时间推进过期
        now.set(2_000);
        assertNull(store.get("k1"));
        assertEquals(1, ((Number) store.stats().get("expirations")).longValue());
    }

    @Test
    void LRU淘汰与租户配额() {
        PrefixCacheStore store = new PrefixCacheStore(3, 50, () -> 1_000);
        store.put("a", "t1", "1", 60_000);
        store.put("b", "t1", "2", 60_000);
        store.put("c", "t2", "3", 60_000);
        store.get("a"); // 刷新 a
        store.put("d", "t1", "4", 60_000); // 淘汰 b（最久未访问）
        assertNull(store.get("b"));
        assertEquals("1", store.get("a").response());
        // 租户配额 50%（容量 3 → 2）：t1 已 2 条（a/d），再写被拒
        assertFalse(store.put("e", "t1", "5", 60_000));
        assertTrue(store.put("f", "t2", "6", 60_000));
    }

    @Test
    void 租户隔离与清空() {
        PrefixCacheStore store = store(1_000);
        store.put("k1", "t1", "v", 60_000);
        store.put("k2", "t2", "v", 60_000);
        assertEquals(1, store.invalidateTenant("t1"));
        assertNull(store.get("k1"));
        assertEquals("v", store.get("k2").response());
        assertEquals(1, store.invalidateAll());
        assertNull(store.get("k2"));
        assertEquals(0, ((Number) store.stats().get("size")).intValue());
    }
}

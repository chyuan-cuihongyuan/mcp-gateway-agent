package cn.chyuan.ai.domain.llmcache.service;

import cn.chyuan.ai.domain.llmcache.service.PrefixKeyCalculator.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 前缀缓存拦截器单测（工单 0280 AJ4）：HIT/MISS→写入/关闭零调用/流式旁路/租户白名单。
 */
class PrefixCacheInterceptorTest {

    private AtomicLong now;
    private PrefixCacheStore store;
    private CacheMetricsCollector metrics;
    private PrefixCacheInterceptor interceptor;

    @BeforeEach
    void setUp() throws Exception {
        now = new AtomicLong(1_000);
        store = new PrefixCacheStore(64, 50, now::get);
        metrics = new CacheMetricsCollector(null);
        interceptor = new PrefixCacheInterceptor(store, metrics);
        setEnabled(true);
    }

    private void setEnabled(boolean enabled) throws Exception {
        java.lang.reflect.Field field = PrefixCacheInterceptor.class.getDeclaredField("enabled");
        field.setAccessible(true);
        field.set(interceptor, enabled);
    }

    private List<ChatMessage> messages(String... contents) {
        return List.of(contents).stream().map(content -> new ChatMessage("user", content)).toList();
    }

    @Test
    void 全链路命中未命中写入与部分匹配() {
        String tenant = "t1";
        String model = "gpt-5";
        // 首次未命中 → 放行 → 写入
        assertNull(interceptor.lookup(tenant, model, messages("s", "u1"), false));
        assertTrue(interceptor.store(tenant, model, messages("s", "u1"), "resp-A"));
        // 完全相同消息 → 全深度命中
        var hit = interceptor.lookup(tenant, model, messages("s", "u1"), false);
        assertEquals("resp-A", hit.response());
        assertEquals(2, hit.matchedDepth());
        // 会话扩展（同前缀 + 新消息）→ 部分匹配不返回缓存，只记收益
        assertNull(interceptor.lookup(tenant, model, messages("s", "u1", "u2"), false));
        assertTrue(((Number) metrics.snapshot().get("savedTokens")).longValue() > 0);
    }

    @Test
    void 流式与租户白名单与关闭() throws Exception {
        // 流式 lookup 旁路（store 侧流式不缓存由调用方在 cachePut 处保证，见 0280 口径）
        assertNull(interceptor.lookup("t1", "m", messages("a"), true));
        // 白名单
        java.lang.reflect.Field whitelist = PrefixCacheInterceptor.class.getDeclaredField("tenantWhitelist");
        whitelist.setAccessible(true);
        whitelist.set(interceptor, "t9");
        assertNull(interceptor.lookup("t1", "m", messages("a"), false));
        whitelist.set(interceptor, "");
        // 关闭 → 零行为
        setEnabled(false);
        assertNull(interceptor.lookup("t1", "m", messages("a"), false));
        assertFalse(interceptor.store("t1", "m", messages("a"), "r"));
        assertEquals(0, ((Number) metrics.snapshot().get("stored")).intValue());
    }

    @Test
    void 管理清空与命中标记() {
        interceptor.store("t1", "m", messages("a", "b"), "resp");
        var hit = interceptor.lookup("t1", "m", messages("a", "b"), false);
        assertEquals("resp", hit.response());
        // 清空后命中归零
        assertEquals(1, interceptor.clearAll());
        assertNull(interceptor.lookup("t1", "m", messages("a", "b"), false));
        // 树快照
        assertTrue(((Number) interceptor.treeSnapshot().get("nodes")).intValue() >= 1);
    }
}

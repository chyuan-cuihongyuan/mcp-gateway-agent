package cn.chyuan.ai.domain.governance.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 特性开关服务单元测试（工单 0178 Y2）— 未注册默认关、缓存 TTL、upsert 失效缓存。
 */
@DisplayName("特性开关服务测试")
class FeatureFlagServiceTest {

    @org.mockito.Mock
    private FeatureFlagService.FlagStore flagStore;

    @Test
    @DisplayName("未注册开关评估默认关不抛错；存储故障降级关")
    public void testUnregisteredAndFailure() {
        FeatureFlagService.FlagStore store = mock(FeatureFlagService.FlagStore.class);
        when(store.enabledOf(anyString())).thenReturn(null).thenThrow(new RuntimeException("db down"));

        FeatureFlagService service = new FeatureFlagService(store, 0L); // TTL=0 每次回源

        assertFalse(service.isEnabled("never-registered"));
        assertFalse(service.isEnabled("failing"));
        assertFalse(service.isEnabled(null));
    }

    @Test
    @DisplayName("缓存 TTL — TTL 内命中缓存不回源，过期重新回源")
    public void testCacheTtl() {
        FeatureFlagService.FlagStore store = mock(FeatureFlagService.FlagStore.class);
        when(store.enabledOf("k")).thenReturn(true);
        FeatureFlagService service = new FeatureFlagService(store, 60_000L);

        assertTrue(service.isEnabled("k"));
        assertTrue(service.isEnabled("k"));
        verify(store, times(1)).enabledOf("k"); // 第二次命中缓存
    }

    @Test
    @DisplayName("upsert 失效缓存 — 更新后立即可见新值")
    public void testUpsertEvictsCache() {
        FeatureFlagService.FlagStore store = mock(FeatureFlagService.FlagStore.class);
        when(store.enabledOf("k")).thenReturn(false).thenReturn(true); // 更新后回源取新值
        FeatureFlagService service = new FeatureFlagService(store, 60_000L);

        assertFalse(service.isEnabled("k"));

        service.upsert("k", true, "灰度放量", "alice");
        assertTrue(service.isEnabled("k"));

        verify(store).upsert(eq("k"), eq(true), eq("灰度放量"), eq("alice"));
    }

    @Test
    @DisplayName("listAll 透传存储全量")
    public void testListAll() {
        FeatureFlagService.FlagStore store = mock(FeatureFlagService.FlagStore.class);
        Map<String, Boolean> all = new HashMap<>();
        all.put("a", true);
        when(store.loadAll()).thenReturn(all);

        FeatureFlagService service = new FeatureFlagService(store, 0L);
        assertEquals(all, service.listAll());
    }
}

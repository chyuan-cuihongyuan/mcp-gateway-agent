package cn.chyuan.ai.domain.governance.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 特性开关服务（工单 0178 Y2，借鉴 Unleash/OpenFeature）—
 * 未注册开关评估=默认关（false，不抛错）；本地缓存 TTL 失效；变更发 FLAG_CHANGE 事件留痕。
 */
@Slf4j
@Service
public class FeatureFlagService {

    /** 开关持久化端口（infrastructure 经 MyBatis 落 feature_flag 表） */
    public interface FlagStore {
        /** 评估：未注册返回 null */
        Boolean enabledOf(String flagKey);

        void upsert(String flagKey, boolean enabled, String note, String operator);

        Map<String, Boolean> loadAll();
    }

    private static class CacheEntry {
        final Boolean enabled;
        final long expiresAtMs;

        CacheEntry(Boolean enabled, long expiresAtMs) {
            this.enabled = enabled;
            this.expiresAtMs = expiresAtMs;
        }
    }

    private final FlagStore flagStore;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Value("${governance.flags.cache-ttl-ms:30000}")
    private long cacheTtlMs;

    public FeatureFlagService(FlagStore flagStore) {
        this.flagStore = flagStore;
    }

    /** 测试专用：显式 TTL */
    public FeatureFlagService(FlagStore flagStore, long cacheTtlMs) {
        this.flagStore = flagStore;
        this.cacheTtlMs = cacheTtlMs;
    }

    /** 评估：未注册/存储故障 → false（默认关不抛错） */
    public boolean isEnabled(String flagKey) {
        if (flagKey == null || flagKey.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        CacheEntry entry = cache.get(flagKey);
        if (entry != null && now < entry.expiresAtMs) {
            return Boolean.TRUE.equals(entry.enabled);
        }
        Boolean enabled;
        try {
            enabled = flagStore.enabledOf(flagKey.trim());
        } catch (Exception e) {
            log.warn("特性开关读取失败（默认关）: key={}, err={}", flagKey, e.getMessage());
            return false;
        }
        if (enabled == null) {
            cache.put(flagKey, new CacheEntry(false, now + cacheTtlMs));
            return false;
        }
        cache.put(flagKey, new CacheEntry(enabled, now + cacheTtlMs));
        return enabled;
    }

    /** 注册/更新开关（清缓存即时生效）；变更由事件发布方（触发方）留痕 */
    public void upsert(String flagKey, boolean enabled, String note, String operator) {
        if (flagKey == null || flagKey.isBlank()) {
            throw new IllegalArgumentException("flagKey 不能为空");
        }
        flagStore.upsert(flagKey.trim(), enabled, note,
                operator == null || operator.isBlank() ? "unknown" : operator.trim());
        cache.remove(flagKey.trim());
    }

    /** 全量开关（key→enabled） */
    public Map<String, Boolean> listAll() {
        return flagStore.loadAll();
    }
}

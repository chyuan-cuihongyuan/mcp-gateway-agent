package cn.chyuan.ai.domain.governance.cache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 治理面元数据短 TTL 缓存（0011 决策②：30 秒 TTL，变更最迟 30s 生效）
 *
 * <p>纯 Java 实现（domain 不引框架）；单实例内存缓存，
 * 同实例写路径调用 {@link #invalidate} 即时失效。
 *
 * @author chyuan
 */
public class TtlCache<V> {

    private final long ttlMillis;
    private final Map<String, Entry<V>> store = new ConcurrentHashMap<>();

    public TtlCache(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    /**
     * 取值：命中且未过期直接返回；否则加载、缓存并返回（加载异常不缓存、原样上抛）。
     */
    public V get(String key, Supplier<V> loader) {
        Entry<V> entry = store.get(key);
        long now = System.currentTimeMillis();
        if (entry != null && now - entry.loadedAt < ttlMillis) {
            return entry.value;
        }
        V value = loader.get();
        store.put(key, new Entry<>(value, now));
        return value;
    }

    public void invalidate(String key) {
        store.remove(key);
    }

    public void invalidateAll() {
        store.clear();
    }

    private record Entry<V>(V value, long loadedAt) {
    }
}

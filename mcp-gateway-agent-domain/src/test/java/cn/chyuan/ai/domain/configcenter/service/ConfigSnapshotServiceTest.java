package cn.chyuan.ai.domain.configcenter.service;

import cn.chyuan.ai.domain.configcenter.service.ConfigSnapshotService.ConfigSnapshot;
import cn.chyuan.ai.domain.configcenter.service.ConfigSnapshotService.SnapshotStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置快照版本服务单测（工单 0251 AG1）：版本递增/同键单一 CURRENT 不变式/回滚/时间线。
 */
class ConfigSnapshotServiceTest {

    private MemoryStore store;
    private ConfigSnapshotService service;

    @BeforeEach
    void setUp() {
        store = new MemoryStore();
        service = new ConfigSnapshotService(store);
    }

    @Test
    void 发布版本号递增且单一CURRENT() {
        ConfigSnapshot v1 = service.publish("ns", "k", "a=1", "m1", false, "op", null);
        ConfigSnapshot v2 = service.publish("ns", "k", "a=2", "m2", false, "op", null);
        assertEquals(1, v1.version());
        assertEquals(2, v2.version());
        assertEquals(ConfigSnapshotService.STATUS_SUPERSEDED, service.get("ns", "k", 1).status());
        assertTrue(service.get("ns", "k", 2).current());
        assertEquals(1, store.rows.values().stream()
                .filter(s -> s.namespace().equals("ns") && s.configKey().equals("k") && s.current()).count());
        // 同键隔离
        assertEquals(1, service.publish("ns", "other", "x", "mx", false, "op", null).version());
    }

    @Test
    void 回滚以新快照承载历史内容() {
        service.publish("ns", "k", "a=1", "m1", false, "op", null);
        service.publish("ns", "k", "a=2", "m2", false, "op", null);
        ConfigSnapshot rolled = service.rollback("ns", "k", 1, "op", null);
        assertEquals(3, rolled.version());
        assertEquals("a=1", rolled.content());
        // 历史 v1 不可变
        assertEquals("a=1", service.get("ns", "k", 1).content());
        assertEquals(ConfigSnapshotService.STATUS_SUPERSEDED, service.get("ns", "k", 1).status());
        // 不存在的版本回滚拒绝
        assertThrows(IllegalArgumentException.class, () -> service.rollback("ns", "k", 99, "op", null));
    }

    @Test
    void 时间线倒序与当前解析() {
        service.publish("ns", "k", "c1", "m1", false, "op", null);
        service.publish("ns", "k", "c2", "m2", false, "op", null);
        List<Integer> versions = service.timeline("ns", "k").stream().map(ConfigSnapshot::version).toList();
        assertEquals(List.of(2, 1), versions);
        assertEquals(2, service.current("ns", "k").version());
        assertNull(service.current("ns", "missing"));
    }

    @Test
    void 非法入参拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConfigSnapshot(1L, "", "k", 1, "c", "m", false, "op", null, "CURRENT"));
        assertThrows(IllegalArgumentException.class,
                () -> new ConfigSnapshot(1L, "ns", "k", 1, "", "m", false, "op", null, "CURRENT"));
        assertThrows(IllegalArgumentException.class,
                () -> new ConfigSnapshot(1L, "ns", "k", 1, "c", "m", false, "op", null, "BOGUS"));
        assertFalse(new ConfigSnapshot(1L, "ns", "k", 1, "c", "m", false, "op", null,
                ConfigSnapshotService.STATUS_SUPERSEDED).current());
    }

    /** 内存快照存储（测试用） */
    private static class MemoryStore implements SnapshotStore {
        private final Map<String, ConfigSnapshot> rows = new ConcurrentHashMap<>();
        private final AtomicLong ids = new AtomicLong();

        private static String key(ConfigSnapshot s) {
            return s.namespace() + "/" + s.configKey() + "/" + s.version();
        }

        @Override
        public int maxVersionOf(String namespace, String configKey) {
            return rows.values().stream()
                    .filter(s -> s.namespace().equals(namespace) && s.configKey().equals(configKey))
                    .mapToInt(ConfigSnapshot::version).max().orElse(0);
        }

        @Override
        public void insert(ConfigSnapshot snapshot) {
            ConfigSnapshot withId = new ConfigSnapshot(ids.incrementAndGet(), snapshot.namespace(),
                    snapshot.configKey(), snapshot.version(), snapshot.content(), snapshot.contentMd5(),
                    snapshot.sensitive(), snapshot.publisher(), snapshot.note(), snapshot.status());
            rows.put(key(withId), withId);
        }

        @Override
        public void update(ConfigSnapshot snapshot) {
            rows.put(key(snapshot), snapshot);
        }

        @Override
        public ConfigSnapshot find(String namespace, String configKey, int version) {
            return rows.get(namespace + "/" + configKey + "/" + version);
        }

        @Override
        public List<ConfigSnapshot> listByKey(String namespace, String configKey) {
            List<ConfigSnapshot> out = new ArrayList<>();
            rows.values().stream()
                    .filter(s -> s.namespace().equals(namespace) && s.configKey().equals(configKey))
                    .forEach(out::add);
            out.sort(Comparator.comparingInt(ConfigSnapshot::version));
            return out;
        }

        @Override
        public List<ConfigSnapshot> listAll() {
            return new ArrayList<>(rows.values());
        }

        @Override
        public List<ConfigSnapshot> listByNamespace(String namespace) {
            return rows.values().stream().filter(s -> s.namespace().equals(namespace)).toList();
        }
    }
}

package cn.chyuan.ai.domain.configkernel.service;

import cn.chyuan.ai.domain.configcenter.service.ConfigCenterFacade;
import cn.chyuan.ai.domain.configcenter.service.ConfigSchemaGate;
import cn.chyuan.ai.domain.configcenter.service.ConfigSnapshotService;
import cn.chyuan.ai.domain.configcenter.service.EnvelopeCipher;
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
 * 配置内核 BD8 端口+组合管线单测（工单 0471）：
 * 端口四能力面（get/put/watch/lease）/configkernel↔configcenter 只读联动/config-kernel.enabled 默认关。
 */
class ConfigKernelFacadeTest {

    @Test
    void BD8_端口组合管线() {
        ConfigKernelPort.InMemoryConfigKernel kernel = new ConfigKernelPort.InMemoryConfigKernel();
        long r1 = kernel.put("app/name", "gw");
        long r2 = kernel.put("app/timeout", "30");
        assertTrue(r1 < r2);
        assertEquals("gw", kernel.get("app/name"));
        assertEquals(r2, kernel.currentRevision());
        List<WatchStream.Event> events = kernel.watch("app/", 1);
        assertEquals(2, events.size(), "watch 历史补发含 put 事件");
        assertEquals(WatchStream.EventType.PUT, events.get(0).type());
        long lease = kernel.grantLease(1_000L);
        kernel.bindKeyToLease(lease, "app/timeout");
        List<String> cascaded = kernel.revokeLease(lease);
        assertEquals(List.of("app/timeout"), cascaded, "租约撤销级联删键");
        assertNull(kernel.get("app/timeout"));
    }

    @Test
    void BD8_门面联动与默认关语义() {
        ConfigCenterFacade center = configCenterWithPublished();
        center.publish("demo", "pool.size", "16", false, "tester", "十期联动测试");
        ConfigKernelFacade disabled = new ConfigKernelFacade(new ConfigKernelPort.InMemoryConfigKernel(),
                center, false);
        assertFalse(disabled.enabled());
        assertEquals("16", disabled.mirrorFromConfigCenter("demo", "pool.size"), "关闭时透传门面原值");
        assertThrows(IllegalStateException.class, () -> disabled.put("demo/pool.size", "32"), "默认关拒绝内核写");
        assertThrows(IllegalStateException.class, () -> disabled.watch("demo/", 1));
        assertEquals("16", disabled.get("demo", "pool.size"), "关闭时读走门面");

        ConfigKernelFacade enabled = new ConfigKernelFacade(new ConfigKernelPort.InMemoryConfigKernel(),
                center, true);
        assertEquals("16", enabled.mirrorFromConfigCenter("demo", "pool.size"));
        assertEquals("16", enabled.get("demo", "pool.size"), "镜像后内核读命中");
        enabled.put("demo/pool.size", "32");
        assertEquals("32", enabled.get("demo", "pool.size"), "内核写覆盖镜像");
        assertEquals("16", center.resolve("demo", "pool.size"), "只读联动不回写门面");
        List<WatchStream.Event> events = enabled.watch("demo/", 1);
        assertFalse(events.isEmpty(), "开启后 watch 可用");
    }

    private ConfigCenterFacade configCenterWithPublished() {
        return new ConfigCenterFacade(new ConfigSnapshotService(new MemSnapshotStore()), new ConfigSchemaGate(),
                new EnvelopeCipher(() -> "test-kek-secret-0123456789"));
    }

    /** 内存快照存储假实现（测试用） */
    private static final class MemSnapshotStore implements ConfigSnapshotService.SnapshotStore {

        private final Map<String, List<ConfigSnapshotService.ConfigSnapshot>> rows = new ConcurrentHashMap<>();
        private final AtomicLong ids = new AtomicLong();

        @Override
        public int maxVersionOf(String namespace, String configKey) {
            return listByKey(namespace, configKey).stream()
                    .mapToInt(ConfigSnapshotService.ConfigSnapshot::version).max().orElse(0);
        }

        @Override
        public void insert(ConfigSnapshotService.ConfigSnapshot snapshot) {
            rows.computeIfAbsent(snapshot.namespace() + "/" + snapshot.configKey(), k -> new ArrayList<>())
                    .add(new ConfigSnapshotService.ConfigSnapshot(ids.incrementAndGet(),
                            snapshot.namespace(), snapshot.configKey(), snapshot.version(),
                            snapshot.content(), snapshot.contentMd5(), snapshot.sensitive(),
                            snapshot.publisher(), snapshot.note(), snapshot.status()));
        }

        @Override
        public void update(ConfigSnapshotService.ConfigSnapshot snapshot) {
            String rowKey = snapshot.namespace() + "/" + snapshot.configKey();
            List<ConfigSnapshotService.ConfigSnapshot> list = rows.get(rowKey);
            if (list == null) {
                return;
            }
            list.removeIf(row -> row.version() == snapshot.version());
            list.add(snapshot);
        }

        @Override
        public ConfigSnapshotService.ConfigSnapshot find(String namespace, String configKey, int version) {
            return listByKey(namespace, configKey).stream()
                    .filter(row -> row.version() == version).findFirst().orElse(null);
        }

        @Override
        public List<ConfigSnapshotService.ConfigSnapshot> listByKey(String namespace, String configKey) {
            List<ConfigSnapshotService.ConfigSnapshot> list =
                    rows.getOrDefault(namespace + "/" + configKey, new ArrayList<>());
            list.sort(Comparator.comparingInt(ConfigSnapshotService.ConfigSnapshot::version));
            return new ArrayList<>(list);
        }

        @Override
        public List<ConfigSnapshotService.ConfigSnapshot> listAll() {
            return rows.values().stream().flatMap(List::stream).toList();
        }

        @Override
        public List<ConfigSnapshotService.ConfigSnapshot> listByNamespace(String namespace) {
            return rows.entrySet().stream().filter(e -> e.getKey().startsWith(namespace + "/"))
                    .flatMap(e -> e.getValue().stream()).toList();
        }
    }
}

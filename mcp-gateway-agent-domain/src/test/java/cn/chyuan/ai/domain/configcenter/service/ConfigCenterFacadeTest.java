package cn.chyuan.ai.domain.configcenter.service;

import cn.chyuan.ai.domain.configcenter.service.ConfigSnapshotService.ConfigSnapshot;
import cn.chyuan.ai.domain.configcenter.service.ConfigSnapshotService.SnapshotStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置中心门面单测（工单 0253 AG3 + 0254 AG4 挂点）：schema 门拦截/敏感项透明加解密/
 * bundle 导入导出（工单 0259 AG9 联动）。
 */
class ConfigCenterFacadeTest {

    private ConfigCenterFacade facade;
    private MemoryStore store;
    private ConfigSchemaGate gate;
    private EnvelopeCipher cipher;

    @BeforeEach
    void setUp() throws Exception {
        store = new MemoryStore();
        gate = new ConfigSchemaGate();
        cipher = new EnvelopeCipher(() -> "test-kek-secret");
        facade = new ConfigCenterFacade(store.rows, gate, cipher);
        setEncryption(true);
    }

    private void setEncryption(boolean enabled) throws Exception {
        setEncryptionOn(facade, enabled);
    }

    private static void setEncryptionOn(ConfigCenterFacade target, boolean enabled) throws Exception {
        java.lang.reflect.Field field = ConfigCenterFacade.class.getDeclaredField("encryptionEnabled");
        field.setAccessible(true);
        field.set(target, enabled);
    }

    @Test
    void 发布链路_schema门拦截与放行() {
        gate.register("ns", "{\"type\":\"object\",\"required\":[\"qps\"],"
                + "\"properties\":{\"qps\":{\"type\":\"number\",\"minimum\":1}}}");
        try {
            facade.publish("ns", "rate", "{\"mode\":\"fixed\"}", false, "op", null);
            org.junit.jupiter.api.Assertions.fail("缺 qps 应被拦截");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("32026"));
            assertTrue(e.getMessage().contains("qps"));
        }
        // 无 schema 命名空间放行
        facade.publish("other", "rate", "{\"mode\":\"fixed\"}", false, "op", null);
        assertEquals("{\"mode\":\"fixed\"}", facade.resolve("other", "rate"));
        // 合法内容过门
        facade.publish("ns", "rate", "{\"qps\":10}", false, "op", null);
        assertEquals(10, ((Number) com.alibaba.fastjson.JSON.parseObject(facade.resolve("ns", "rate"))
                .get("qps")).intValue());
    }

    @Test
    void 敏感项透明加解密与密文落库() throws Exception {
        facade.publish("ns", "secret", "{\"apikey\":\"sk-123\"}", true, "op", null);
        // 存储为密文
        ConfigSnapshot current = store.rows.current("ns", "secret");
        assertTrue(EnvelopeCipher.isToken(current.content()));
        assertTrue(current.sensitive());
        // 读取透明解密
        assertEquals("{\"apikey\":\"sk-123\"}", facade.resolve("ns", "secret"));
        // 加密关闭后同内容明文直存
        setEncryption(false);
        facade.publish("ns", "plain", "{\"apikey\":\"sk-456\"}", true, "op", null);
        assertEquals("{\"apikey\":\"sk-456\"}", store.rows.current("ns", "plain").content());
    }

    @Test
    void 回滚沿用密文且读取解密() {
        facade.publish("ns", "secret", "v1-secret", true, "op", null);
        facade.publish("ns", "secret", "v2-secret", true, "op", null);
        facade.rollback("ns", "secret", 1, "op", null);
        assertEquals("v1-secret", facade.resolve("ns", "secret"));
        assertTrue(EnvelopeCipher.isToken(store.rows.current("ns", "secret").content()));
    }

    @Test
    void bundle导出导入往返与校验和() throws Exception {
        setEncryption(true);
        facade.publish("ns", "a", "{\"x\":1}", false, "op", null);
        facade.publish("ns", "sec", "enc-me", true, "op", null);
        ConfigBundleCodec.ConfigBundle bundle = facade.exportBundle("ns");
        assertTrue(EnvelopeCipher.isToken(bundle.entries().stream()
                .filter(e -> e.configKey().equals("sec")).findFirst().orElseThrow().content()));
        // 另一"环境"（空 store）导入 skip 策略 → 全新增
        ConfigCenterFacade other = new ConfigCenterFacade(new MemoryStore().rows, new ConfigSchemaGate(), cipher);
        setEncryptionOn(other, true);
        ConfigBundleCodec.ImportReport report = other.importBundle(ConfigBundleCodec.toJson(bundle), "skip");
        assertTrue(report.success());
        assertEquals(2, report.added());
        assertEquals("enc-me", other.resolve("ns", "sec"));
        // 校验和防篡改（重建非法校验和 bundle）
        ConfigBundleCodec.ConfigBundle parsed = ConfigBundleCodec.fromJson(ConfigBundleCodec.toJson(bundle));
        String tampered = ConfigBundleCodec.toJson(new ConfigBundleCodec.ConfigBundle(
                parsed.namespace(), parsed.entries(), "bogus-checksum"));
        ConfigBundleCodec.ImportReport bad = other.importBundle(tampered, "overwrite");
        assertFalse(bad.success());
    }

    @Test
    void bundle导入_schema门全不落库() {
        gate.register("ns", "{\"type\":\"object\",\"properties\":{\"qps\":{\"type\":\"number\"}}}");
        facade.publish("ns", "a", "{\"qps\":1}", false, "op", null);
        // 同命名空间违规项验证拦截
        String violating = ConfigBundleCodec.toJson(ConfigBundleCodec.export("ns", List.of(
                new ConfigBundleCodec.BundleEntry("bad", 1, "{\"qps\":\"nan\"}", "m", false))));
        try {
            facade.importBundle(violating, "overwrite");
            org.junit.jupiter.api.Assertions.fail("违规项应拦截");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("schema"));
        }
        // 未触碰现有数据
        assertEquals(1, store.rows.listByNamespace("ns").stream()
                .filter(ConfigSnapshot::current).count());
        assertFalse(store.rows.listByNamespace("ns").stream().anyMatch(s -> s.configKey().equals("bad")));
    }

    /** 内存快照存储（复用语义） */
    static class MemoryStore implements SnapshotStore {
        final ConcurrentHashMap<String, ConfigSnapshot> map = new ConcurrentHashMap<>();
        private final AtomicLong ids = new AtomicLong();
        final ConfigSnapshotService rows = new ConfigSnapshotService(this);

        @Override
        public int maxVersionOf(String namespace, String configKey) {
            return map.values().stream()
                    .filter(s -> s.namespace().equals(namespace) && s.configKey().equals(configKey))
                    .mapToInt(ConfigSnapshot::version).max().orElse(0);
        }

        @Override
        public void insert(ConfigSnapshot snapshot) {
            ConfigSnapshot withId = new ConfigSnapshot(ids.incrementAndGet(), snapshot.namespace(),
                    snapshot.configKey(), snapshot.version(), snapshot.content(), snapshot.contentMd5(),
                    snapshot.sensitive(), snapshot.publisher(), snapshot.note(), snapshot.status());
            map.put(withId.namespace() + "/" + withId.configKey() + "/" + withId.version(), withId);
        }

        @Override
        public void update(ConfigSnapshot snapshot) {
            map.put(snapshot.namespace() + "/" + snapshot.configKey() + "/" + snapshot.version(), snapshot);
        }

        @Override
        public ConfigSnapshot find(String namespace, String configKey, int version) {
            return map.get(namespace + "/" + configKey + "/" + version);
        }

        @Override
        public List<ConfigSnapshot> listByKey(String namespace, String configKey) {
            List<ConfigSnapshot> out = new ArrayList<>();
            map.values().stream()
                    .filter(s -> s.namespace().equals(namespace) && s.configKey().equals(configKey))
                    .forEach(out::add);
            out.sort(java.util.Comparator.comparingInt(ConfigSnapshot::version));
            return out;
        }

        @Override
        public List<ConfigSnapshot> listAll() {
            return new ArrayList<>(map.values());
        }

        @Override
        public List<ConfigSnapshot> listByNamespace(String namespace) {
            return new ArrayList<>(map.values().stream()
                    .filter(s -> s.namespace().equals(namespace)).toList());
        }

        ConfigSnapshot current(String namespace, String configKey) {
            return map.values().stream()
                    .filter(s -> s.namespace().equals(namespace) && s.configKey().equals(configKey)
                            && s.current())
                    .findFirst().orElse(null);
        }
    }
}

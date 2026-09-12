package cn.chyuan.ai.domain.promptresource.service;

import cn.chyuan.ai.domain.promptresource.service.PromptVersionService.PromptVersion;
import cn.chyuan.ai.domain.promptresource.service.PromptVersionService.VersionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提示版本服务单测（工单 0196 AA1）：版本递增/发布不变式/回滚/解析回退。
 */
class PromptVersionServiceTest {

    private MemoryStore store;
    private PromptVersionService service;

    @BeforeEach
    void setUp() {
        store = new MemoryStore();
        service = new PromptVersionService(store);
    }

    @Test
    void 草稿版本号递增() {
        PromptVersion v1 = service.createDraft("sys", "你好 {{name}}", "n1", "op");
        PromptVersion v2 = service.createDraft("sys", "你好 {{name}} v2", "n2", "op");
        assertEquals(1, v1.version());
        assertEquals(2, v2.version());
        assertEquals(3, service.createDraft("sys", "v3", null, "op").version());
        // 同名隔离
        assertEquals(1, service.createDraft("other", "x", null, "op").version());
    }

    @Test
    void 发布单一PUBLISHED不变式() {
        PromptVersion v1 = service.createDraft("sys", "v1", null, "op");
        PromptVersion v2 = service.createDraft("sys", "v2", null, "op");
        service.publish("sys", v1.version(), "op");
        assertTrue(service.get("sys", 1).published());
        service.publish("sys", v2.version(), "op");
        // 前任转 ROLLBACK，同名单一 PUBLISHED
        assertEquals(PromptVersionService.STATUS_ROLLBACK, service.get("sys", 1).status());
        assertTrue(service.get("sys", 2).published());
        assertEquals(1, store.rows.values().stream()
                .filter(v -> v.promptName().equals("sys") && v.published()).count());
    }

    @Test
    void 回滚重发布() {
        service.createDraft("sys", "v1", null, "op");
        service.createDraft("sys", "v2", null, "op");
        service.publish("sys", 1, "op");
        service.publish("sys", 2, "op");
        PromptVersion rolled = service.rollback("sys", 1, "op");
        assertEquals(1, rolled.version());
        assertTrue(rolled.published());
        assertFalse(service.get("sys", 2).published());
        // 草稿不可回滚目标
        service.createDraft("sys", "v3-draft", null, "op");
        assertThrows(IllegalArgumentException.class, () -> service.rollback("sys", 3, "op"));
    }

    @Test
    void 解析回退规则() {
        assertNull(service.resolvePublished("none"));
        service.createDraft("sys", "draft-only", null, "op");
        // 无发布 → 最高版本
        assertEquals("draft-only", service.resolvePublished("sys").template());
        service.createDraft("sys", "v2", null, "op");
        service.publish("sys", 2, "op");
        assertEquals(2, service.resolvePublished("sys").version());
    }

    @Test
    void 打标签与解析() {
        service.createDraft("sys", "v1", null, "op");
        service.createDraft("sys", "v2", null, "op");
        service.publish("sys", 1, "op");
        // staging 打在 v2（未发布版本也允许，便于预览）
        service.attachLabel("sys", 2, PromptVersionService.LABEL_STAGING);
        assertEquals(2, service.resolveByLabel("sys", PromptVersionService.LABEL_STAGING).version());
        // production 未打标 → 回退已发布 v1
        assertEquals(1, service.resolveByLabel("sys", PromptVersionService.LABEL_PRODUCTION).version());
        // 打 production 在 v2 → 同名单一标签持有，staging 被清空，解析回退已发布 v1
        service.attachLabel("sys", 2, PromptVersionService.LABEL_PRODUCTION);
        assertNull(store.findByLabel("sys", PromptVersionService.LABEL_STAGING));
        assertEquals(2, service.resolveByLabel("sys", PromptVersionService.LABEL_PRODUCTION).version());
        // 非法标签拒绝
        assertThrows(IllegalArgumentException.class, () -> service.attachLabel("sys", 1, "bogus"));
        // 完全未知提示 → 回退链尽头 null
        assertNull(service.resolveByLabel("none", PromptVersionService.LABEL_PRODUCTION));
    }

    @Test
    void 非法值对象与缺失版本() {
        assertThrows(IllegalArgumentException.class,
                () -> new PromptVersion(null, "", 1, "t", "DRAFT", null, "op", null));
        assertThrows(IllegalArgumentException.class,
                () -> new PromptVersion(null, "n", 1, "", "DRAFT", null, "op", null));
        assertThrows(IllegalArgumentException.class,
                () -> new PromptVersion(null, "n", 1, "t", "BOGUS", null, "op", null));
        assertThrows(IllegalArgumentException.class,
                () -> new PromptVersion(null, "n", 1, "t", "DRAFT", null, "op", "bogus"));
        assertThrows(IllegalArgumentException.class, () -> service.get("sys", 99));
        assertThrows(IllegalArgumentException.class, () -> service.publish("sys", 99, "op"));
    }

    /** 内存存储（并发容器保证测试内线程安全语义与生产一致） */
    static class MemoryStore implements VersionStore {
        final Map<String, PromptVersion> rows = new ConcurrentHashMap<>();
        final Map<String, Integer> maxVersion = new ConcurrentHashMap<>();
        final AtomicLong idGen = new AtomicLong();
        final List<PromptVersion> inserted = new CopyOnWriteArrayList<>();

        private String key(String name, int version) {
            return name + "#" + version;
        }

        @Override
        public int maxVersionOf(String promptName) {
            return maxVersion.getOrDefault(promptName, 0);
        }

        @Override
        public synchronized void insert(PromptVersion version) {
            PromptVersion withId = new PromptVersion(idGen.incrementAndGet(), version.promptName(),
                    version.version(), version.template(), version.status(), version.note(),
                    version.operator(), version.label());
            rows.put(key(withId.promptName(), withId.version()), withId);
            maxVersion.merge(withId.promptName(), withId.version(), Math::max);
            inserted.add(withId);
        }

        @Override
        public synchronized void update(PromptVersion version) {
            rows.put(key(version.promptName(), version.version()), version);
        }

        @Override
        public synchronized PromptVersion find(String promptName, int version) {
            return rows.get(key(promptName, version));
        }

        @Override
        public synchronized List<PromptVersion> listByName(String promptName) {
            List<PromptVersion> out = new ArrayList<>();
            for (PromptVersion v : rows.values()) {
                if (v.promptName().equals(promptName)) {
                    out.add(v);
                }
            }
            return out;
        }

        @Override
        public synchronized List<PromptVersion> listAll() {
            return new ArrayList<>(rows.values());
        }

        @Override
        public synchronized PromptVersion findByLabel(String promptName, String label) {
            for (PromptVersion v : rows.values()) {
                if (v.promptName().equals(promptName) && label.equals(v.label())) {
                    return v;
                }
            }
            return null;
        }

        @Override
        public synchronized void clearLabels(String promptName) {
            for (Map.Entry<String, PromptVersion> e : rows.entrySet()) {
                PromptVersion v = e.getValue();
                if (v.promptName().equals(promptName) && v.label() != null) {
                    e.setValue(new PromptVersion(v.id(), v.promptName(), v.version(), v.template(),
                            v.status(), v.note(), v.operator(), null));
                }
            }
        }
    }
}

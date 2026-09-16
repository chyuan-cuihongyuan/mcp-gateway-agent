package cn.chyuan.ai.domain.modelcatalog.service;

import cn.chyuan.ai.domain.modelcatalog.service.ModelCatalogService.ModelEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型目录服务单测（工单 0281 AJ5）：注册去重/计价联动/context 预检/校验拒绝。
 */
class ModelCatalogServiceTest {

    private ConcurrentHashMap<String, ModelEntry> rows;
    private ModelCatalogService service;

    @BeforeEach
    void setUp() {
        rows = new ConcurrentHashMap<>();
        // 假计价表：仅 100 号条目存在（与目录行解耦，避免自探测假阳性）
        service = new ModelCatalogService(new InMemoryStore(rows), pricingId -> pricingId == 100L);
    }

    @Test
    void 注册去重与计价联动() {
        ModelEntry entry = service.register(new ModelEntry(null, "gpt-5", 128_000,
                Set.of("text", "vision"), 7L, null, null, "op"));
        assertEquals(1L, entry.id());
        assertTrue(entry.active());
        // 计价条目缺失→警告不阻断
        assertFalse(service.checkPricing(entry).linked());
        assertTrue(service.checkPricing(entry).warned());
        // 计价条目存在（100 号）→联动成功不告警
        ModelEntry linked = service.register(new ModelEntry(null, "gpt-5-mini", 8_000,
                Set.of("text"), 100L, null, null, "op"));
        assertTrue(service.checkPricing(linked).linked());
        assertFalse(service.checkPricing(linked).warned());
        // 重复注册拒绝
        assertThrows(IllegalArgumentException.class, () -> service.register(new ModelEntry(null, "gpt-5",
                8_000, Set.of("text"), null, null, null, "op")));
        // 非法模态与状态拒绝
        assertThrows(IllegalArgumentException.class, () -> new ModelEntry(null, "m", 100,
                Set.of("smell"), null, null, null, "op"));
        assertThrows(IllegalArgumentException.class, () -> new ModelEntry(null, "m", 100,
                null, null, "BOGUS", null, "op"));
        assertThrows(IllegalArgumentException.class, () -> new ModelEntry(null, "m", -1,
                null, null, null, null, "op"));
    }

    @Test
    void context预检与检索() {
        service.register(new ModelEntry(null, "small", 1_000, Set.of("text"), null, null, null, "op"));
        assertTrue(service.assertContextWithinLimit("small", 999));
        assertFalse(service.assertContextWithinLimit("small", 1_001));
        // 不在目录 → 预检放行（目录外模型不拦截）
        assertTrue(service.assertContextWithinLimit("unknown", 999_999));
        assertEquals("small", service.get("small").model());
        assertThrows(IllegalArgumentException.class, () -> service.get("nope"));
        assertEquals(1, service.listAll().size());
    }

    /** 内存目录存储 */
    private static class InMemoryStore implements ModelCatalogService.CatalogStore {
        private final ConcurrentHashMap<String, ModelEntry> rows;

        InMemoryStore(ConcurrentHashMap<String, ModelEntry> rows) {
            this.rows = rows;
        }

        @Override
        public void insert(ModelEntry entry) {
            rows.put(entry.model(), entry);
        }

        @Override
        public void update(ModelEntry entry) {
            rows.put(entry.model(), entry);
        }

        @Override
        public ModelEntry findByModel(String model) {
            return rows.get(model);
        }

        @Override
        public List<ModelEntry> listAll() {
            return List.copyOf(rows.values());
        }
    }
}

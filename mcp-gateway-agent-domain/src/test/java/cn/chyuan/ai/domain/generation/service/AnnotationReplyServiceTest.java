package cn.chyuan.ai.domain.generation.service;

import cn.chyuan.ai.domain.generation.service.AnnotationReplyService.AnnotationHit;
import cn.chyuan.ai.domain.generation.service.AnnotationReplyService.AnnotationQa;
import cn.chyuan.ai.domain.generation.service.AnnotationReplyService.AnnotationStore;
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
 * 标注回复单测（工单 0202 AA7）：归一化精确/编辑距离兜底/命中计数/值对象校验。
 */
class AnnotationReplyServiceTest {

    @Test
    void 归一化精确命中() {
        MemoryStore store = new MemoryStore();
        AnnotationReplyService service = new AnnotationReplyService(store);
        store.insert(new AnnotationQa(1L, "退货政策是什么？", "7 天无理由", 0, true, "op"));
        // 大小写/空白差异仍精确命中（归一化）
        AnnotationHit hit = service.find("退货 政策 是什 么？");
        assertNotNull(hit);
        assertFalse(hit.fuzzy());
        assertEquals("7 天无理由", hit.answer());
        // 禁用不命中
        store.rows.get(1L).updateEnabled(false);
        assertNull(service.find("退货政策是什么？"));
    }

    @Test
    void 编辑距离兜底与关闭() {
        MemoryStore store = new MemoryStore();
        store.insert(new AnnotationQa(1L, "如何重置密码", "官网-安全-重置", 0, true, "op"));
        // 阈值 0：兜底关闭
        assertNull(new AnnotationReplyService(store, 0.0).find("怎么重置密码呀"));
        // 阈值 0.5：近似命中（"怎么重置密码呀" vs "如何重置密码" 归一化相似度 ≈ 0.57）
        AnnotationHit hit = new AnnotationReplyService(store, 0.5).find("怎么重置密码呀");
        assertNotNull(hit);
        assertTrue(hit.fuzzy());
        assertEquals("官网-安全-重置", hit.answer());
    }

    @Test
    void 命中计数与异常安全() {
        MemoryStore store = new MemoryStore();
        AnnotationReplyService service = new AnnotationReplyService(store);
        store.insert(new AnnotationQa(7L, "q", "a", 0, true, "op"));
        service.recordHit(service.find("q"));
        assertEquals(1, store.hitOf(7L));
        // null/异常安全
        service.recordHit(null);
        store.boom = true;
        service.recordHit(new AnnotationHit(7L, "a", "q", false));
        assertEquals(1, store.hitOf(7L));
    }

    @Test
    void 相似度与值对象校验() {
        assertEquals(1.0, AnnotationReplyService.similarity("abc", "abc"));
        assertTrue(AnnotationReplyService.similarity("abcd", "abce") > 0.7);
        assertEquals(0.0, AnnotationReplyService.similarity("", "abc"));
        assertThrows(IllegalArgumentException.class,
                () -> new AnnotationQa(1L, "", "a", 0, true, "op"));
        assertThrows(IllegalArgumentException.class,
                () -> new AnnotationQa(1L, "q", " ", 0, true, "op"));
    }

    @Test
    void 未命中与空输入() {
        AnnotationReplyService service = new AnnotationReplyService(new MemoryStore());
        assertNull(service.find("从未见过的长问题"));
        assertNull(service.find(null));
        assertNull(service.find("  "));
    }

    /** 内存存储 */
    static class MemoryStore implements AnnotationStore {
        final Map<Long, MutableQa> rows = new ConcurrentHashMap<>();
        final AtomicLong idGen = new AtomicLong(100);
        volatile boolean boom = false;

        static class MutableQa {
            volatile AnnotationQa qa;

            MutableQa(AnnotationQa qa) {
                this.qa = qa;
            }

            void updateEnabled(boolean enabled) {
                AnnotationQa q = this.qa;
                this.qa = new AnnotationQa(q.id(), q.question(), q.answer(), q.hitCount(), enabled, q.operator());
            }
        }

        @Override
        public void insert(AnnotationQa qa) {
            long id = qa.id() != null ? qa.id() : idGen.incrementAndGet();
            MutableQa m = new MutableQa(qa);
            rows.put(id, m);
        }

        long hitOf(Long id) {
            return rows.get(id).qa.hitCount();
        }

        @Override
        public List<AnnotationQa> listEnabled() {
            List<AnnotationQa> out = new ArrayList<>();
            for (MutableQa m : rows.values()) {
                if (m.qa.enabled()) {
                    out.add(m.qa);
                }
            }
            return out;
        }

        @Override
        public void update(AnnotationQa qa) {
            rows.put(qa.id(), new MutableQa(qa));
        }

        @Override
        public void delete(Long id) {
            rows.remove(id);
        }

        @Override
        public AnnotationQa findById(Long id) {
            MutableQa m = rows.get(id);
            return m == null ? null : m.qa;
        }

        @Override
        public void incrementHit(Long id) {
            if (boom) {
                throw new IllegalStateException("boom");
            }
            MutableQa m = rows.get(id);
            AnnotationQa q = m.qa;
            m.qa = new AnnotationQa(q.id(), q.question(), q.answer(), q.hitCount() + 1, q.enabled(), q.operator());
        }

        @Override
        public AnnotationQa findByKey(String questionKey) {
            for (MutableQa m : rows.values()) {
                if (AnnotationReplyService.normalizeKey(m.qa.question()).equals(questionKey)) {
                    return m.qa;
                }
            }
            return null;
        }
    }
}

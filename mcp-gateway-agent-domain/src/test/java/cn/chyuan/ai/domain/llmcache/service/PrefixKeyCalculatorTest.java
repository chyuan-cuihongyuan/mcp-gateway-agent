package cn.chyuan.ai.domain.llmcache.service;

import cn.chyuan.ai.domain.llmcache.service.PrefixKeyCalculator.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 前缀缓存键内核单测（工单 0277 AJ1）：链式确定性/前缀敏感性/隔离性/边界。
 */
class PrefixKeyCalculatorTest {

    @Test
    void 链式哈希确定性与前缀敏感性() {
        String ns = PrefixKeyCalculator.namespace("gpt-5", "t1");
        List<String> two = PrefixKeyCalculator.prefixKeys(ns, List.of(new ChatMessage("system", "s"), new ChatMessage("user", "u")));
        List<String> again = PrefixKeyCalculator.prefixKeys(ns, List.of(new ChatMessage("system", "s"), new ChatMessage("user", "u")));
        assertEquals(two, again);
        // 前缀键是前缀（追加消息不改前段键）
        List<String> extended = PrefixKeyCalculator.prefixKeys(ns,
                List.of(new ChatMessage("system", "s"), new ChatMessage("user", "u"), new ChatMessage("assistant", "a")));
        assertEquals(two, extended.subList(0, 2));
        // 任一中间块变化 → 后续键全变
        List<String> changedMiddle = PrefixKeyCalculator.prefixKeys(ns,
                List.of(new ChatMessage("system", "s"), new ChatMessage("user", "u2"), new ChatMessage("assistant", "a")));
        assertFalse(changedMiddle.get(2).equals(extended.get(2)));
        assertTrue(!changedMiddle.get(1).equals(extended.get(1)));
    }

    @Test
    void 命名空间隔离与空消息边界() {
        String t1 = PrefixKeyCalculator.namespace("m", "t1");
        String t2 = PrefixKeyCalculator.namespace("m", "t2");
        assertFalse(t1.equals(t2));
        assertEquals(t1, PrefixKeyCalculator.namespace("m", "t1"));
        // 空消息列表
        assertTrue(PrefixKeyCalculator.prefixKeys(t1, List.of()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> PrefixKeyCalculator.fullKey(t1, List.of()));
        // role/content null 兜底
        List<String> keys = PrefixKeyCalculator.prefixKeys(t1, List.of(new ChatMessage(null, null)));
        assertEquals(1, keys.size());
    }
}

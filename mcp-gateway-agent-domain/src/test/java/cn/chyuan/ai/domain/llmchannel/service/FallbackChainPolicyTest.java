package cn.chyuan.ai.domain.llmchannel.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * fallback 链纯函数策略测试（工单 0155：防环校验 + 链解析边界）
 */
@DisplayName("fallback 链纯函数策略测试")
public class FallbackChainPolicyTest {

    @Test
    @DisplayName("防环校验 — 自环（A→A）拒绝保存")
    public void testSelfLoopRejected() {
        assertTrue(FallbackChainPolicy.createsCycle(Map.of(), 1L, 1L), "A→A 自环即环");
    }

    @Test
    @DisplayName("防环校验 — A→B 且 B→A 成环拒绝；A→B（B 无 fallback）安全")
    public void testTwoNodeCycle() {
        // 既有边 B→A；再把 A 指到 B → A→B→A 成环
        Map<Long, Long> edges = Map.of(2L, 1L);
        assertTrue(FallbackChainPolicy.createsCycle(edges, 1L, 2L), "A→B→A 应判定成环");

        // 既有边 A→B；保存 B→C（C 链尾）不成环
        Map<Long, Long> chain = Map.of(1L, 2L);
        assertFalse(FallbackChainPolicy.createsCycle(chain, 2L, 3L), "B→C 链尾安全");
    }

    @Test
    @DisplayName("防环校验 — 长链回指（A→B→C，再令 C→A）拒绝；空图安全")
    public void testLongChainBackEdge() {
        Map<Long, Long> edges = Map.of(1L, 2L, 2L, 3L);
        assertTrue(FallbackChainPolicy.createsCycle(edges, 3L, 1L), "C→A 回指成环");
        assertFalse(FallbackChainPolicy.createsCycle(Map.of(), 1L, 2L), "空图任意指向安全");
        assertFalse(FallbackChainPolicy.createsCycle(null, 1L, 2L), "null 图防御安全");
    }

    @Test
    @DisplayName("链解析 — 链长上限 2 跳截断（A→B→C→D 只取 B、C）")
    public void testResolveChainBounded() {
        Map<Long, Long> edges = Map.of(1L, 2L, 2L, 3L, 3L, 4L);
        List<Long> chain = FallbackChainPolicy.resolveChain(edges, 1L, FallbackChainPolicy.MAX_HOPS);
        assertEquals(List.of(2L, 3L), chain, "至多 2 跳");
    }

    @Test
    @DisplayName("链解析 — 环截断（A→B→A 不死循环）与空链")
    public void testResolveChainCycleSafe() {
        Map<Long, Long> cycle = Map.of(1L, 2L, 2L, 1L);
        List<Long> chain = FallbackChainPolicy.resolveChain(cycle, 1L, FallbackChainPolicy.MAX_HOPS);
        assertEquals(List.of(2L), chain, "回到起点即截断");

        assertTrue(FallbackChainPolicy.resolveChain(Map.of(), 1L, 2).isEmpty(), "无边空链");
        assertTrue(FallbackChainPolicy.resolveChain(cycle, 9L, 2).isEmpty(), "起点不在图上空链");
    }
}

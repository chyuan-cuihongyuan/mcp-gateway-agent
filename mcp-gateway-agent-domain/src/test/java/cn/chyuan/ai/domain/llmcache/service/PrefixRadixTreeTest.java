package cn.chyuan.ai.domain.llmcache.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 前缀基数树单测（工单 0278 AJ2）：插入/最长匹配/上限剪除/收益统计/快照。
 */
class PrefixRadixTreeTest {

    private static List<String> keys(String... values) {
        return List.of(values);
    }

    @Test
    void 插入与最长匹配() {
        PrefixRadixTree tree = new PrefixRadixTree(16, 1024, 100);
        assertTrue(tree.insert(keys("k1", "k2", "k3")));
        assertEquals(3, tree.longestMatch(keys("k1", "k2", "k3")));
        assertEquals(2, tree.longestMatch(keys("k1", "k2", "zz")));
        assertEquals(0, tree.longestMatch(keys("zz", "k2")));
        // 分支插入后另一分支整链匹配（k1→x 为已插入末端）
        assertTrue(tree.insert(keys("k1", "x")));
        assertEquals(2, tree.longestMatch(keys("k1", "x")));
        // 收益逐次累计：3+2+0+2 块 × 100 token/块
        assertEquals(7L * 100, tree.savedTokens());
    }

    @Test
    void 深度与节点数上限() {
        PrefixRadixTree depthLimited = new PrefixRadixTree(2, 1024, 10);
        assertFalse(depthLimited.insert(keys("a", "b", "c")));
        // 节点数上限（root 计 1）：上限 5 时 [a,b] 后（3 节点）[c,d,e] 需 6 节点拒绝、[c,f] 恰好 5 节点可入
        PrefixRadixTree nodeLimited = new PrefixRadixTree(64, 5, 10);
        assertTrue(nodeLimited.insert(keys("a", "b")));
        assertFalse(nodeLimited.insert(keys("c", "d", "e")));
        // 剪除分支
        assertTrue(nodeLimited.insert(keys("c", "f")));
        assertEquals(1, nodeLimited.pruneTail("f"));
        assertEquals(0, nodeLimited.longestMatch(keys("c", "f")));
        assertTrue(nodeLimited.nodeCount() < 10);
    }

    @Test
    void 空序列与快照() {
        PrefixRadixTree tree = new PrefixRadixTree();
        assertFalse(tree.insert(List.of()));
        assertEquals(0, tree.longestMatch(List.of()));
        var snapshot = tree.snapshot();
        assertEquals(1, snapshot.get("nodes"));
        assertFalse(((Number) snapshot.get("savedTokens")).longValue() > 0);
    }
}

package cn.chyuan.ai.domain.crdtkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CRDT 协同内核域单测（工单 0602-0608 BT1-BT7，yjs 思想）。
 * 计数器/寄存器/集合/OR-Set/YATA 序列/版本向量/反熵收敛不变量。
 */
class CrdtKernelTest {

    @Test
    void gCounterMergesByMaxAndPnSubtracts() {
        GCounter a = GCounter.growOnly();
        GCounter b = GCounter.growOnly();
        a.increment("A", 3);
        b.increment("B", 5);
        a.merge(b);
        b.merge(a);
        assertEquals(8L, a.value());
        assertEquals(8L, b.value());
        assertThrows(IllegalArgumentException.class, () -> a.increment("A", -1));
        assertThrows(IllegalArgumentException.class, () -> a.increment(" ", 1));

        GCounter p = GCounter.pnSide();
        GCounter n = GCounter.pnSide();
        p.increment("A", 10);
        n.increment("A", 4);
        assertEquals(6L, GCounter.pnValue(p, n));
    }

    @Test
    void lwwRegisterBreaksTiesByReplicaAndMvKeepsSiblings() {
        Registers.LwwRegister a = new Registers.LwwRegister("v1", 100L, "A");
        Registers.LwwRegister b = new Registers.LwwRegister("v2", 100L, "B");
        a.merge(b);
        b.merge(a);
        assertEquals("v2", a.value(), "平局按副本 id 字典序：B 胜");
        assertEquals("v2", b.value());

        a.write("v3", 50L, "A");
        assertEquals("v2", a.value(), "旧时间戳写不生效");

        Registers.MvRegister mva = new Registers.MvRegister("x", 100L, "A");
        Registers.MvRegister mvb = new Registers.MvRegister("y", 100L, "B");
        mva.merge(mvb);
        mvb.merge(mva);
        assertEquals(List.of("x", "y"), mva.values(), "并发写保留兄弟");
        assertEquals(mva.values(), mvb.values());
        mva.write("z", 200L, "A");
        assertEquals(List.of("z"), mva.values(), "再写清空兄弟");

        Registers.LwwRegister e1 = new Registers.LwwRegister("m", 10L, "A");
        Registers.LwwRegister e2 = new Registers.LwwRegister("m", 10L, "A");
        e1.merge(e2);
        assertEquals("m", e1.value(), "幂等合并");
    }

    @Test
    void setsGrowOnlyAndTombstoneSemantics() {
        GrowSets.GSet g = new GrowSets.GSet();
        g.add("a");
        g.add("a");
        assertEquals(1, g.size());
        assertThrows(IllegalArgumentException.class, () -> g.remove("a"));
        GrowSets.GSet other = new GrowSets.GSet();
        other.add("b");
        g.merge(other);
        assertTrue(g.contains("b"));

        GrowSets.TwoPSet twoP = new GrowSets.TwoPSet();
        twoP.add("x");
        twoP.add("y");
        twoP.remove("x");
        twoP.add("x");
        assertFalse(twoP.contains("x"), "删除后不可再添加（墓碑恒久）");
        assertTrue(twoP.contains("y"));
        GrowSets.TwoPSet mirrored = new GrowSets.TwoPSet();
        mirrored.merge(twoP);
        mirrored.add("x");
        assertFalse(mirrored.contains("x"), "合并传播墓碑");
    }

    @Test
    void orSetSupportsRemoveThenReAddUnderConcurrency() {
        OrSet a = new OrSet();
        a.add("cfg", "A");
        a.remove("cfg");
        assertFalse(a.contains("cfg"));
        a.add("cfg", "A");
        assertTrue(a.contains("cfg"), "删后重加新标签存活");

        OrSet b = new OrSet();
        b.merge(a);
        assertTrue(b.contains("cfg"));
        b.remove("cfg");
        a.merge(b);
        assertFalse(a.contains("cfg"), "墓碑并集传播");

        assertThrows(IllegalArgumentException.class, () -> a.add(null, "A"));
    }

    @Test
    void yataConcurrentInsertsConvergeWithoutInterleaving() {
        YataText a = new YataText();
        YataText b = new YataText();
        for (char ch : "hello".toCharArray()) {
            a.append("seed", ch);
        }
        ConvergenceKit.exchangeYata(a, b);
        assertEquals("hello", b.text());

        YataText.ItemId anchor = a.lastId();
        a.insert("A", anchor, null, 'A');
        b.insert("B", anchor, null, 'B');
        ConvergenceKit.exchangeYata(a, b);
        ConvergenceKit.exchangeYata(b, a);
        assertEquals("helloAB", a.text(), "并发插入按副本 id 定序收敛");
        assertEquals(a.text(), b.text());
        assertEquals(a.order(), b.order());

        a.delete(a.lastId());
        assertEquals("helloA", a.text());
        assertThrows(IllegalArgumentException.class, () -> a.delete(null));
    }

    @Test
    void versionVectorOrdersCausalityAndDetectsConcurrency() {
        VersionVector base = new VersionVector();
        base.tick("A");
        base.tick("A");
        VersionVector follower = VersionVector.fromState(base.state());
        follower.tick("B");
        assertEquals(VersionVector.Relation.DOMINATES, follower.compare(base));
        assertEquals(VersionVector.Relation.DOMINATED_BY, base.compare(follower));
        assertFalse(follower.hasMissingPrecedents(base));

        VersionVector divergent = VersionVector.fromState(base.state());
        divergent.tick("A");
        assertEquals(VersionVector.Relation.CONCURRENT, divergent.compare(follower));
        assertTrue(divergent.hasMissingPrecedents(follower));

        VersionVector merged = VersionVector.fromState(base.state());
        merged.merge(follower);
        merged.merge(divergent);
        assertEquals(VersionVector.Relation.DOMINATES, merged.compare(follower));
        assertEquals(VersionVector.Relation.EQUAL, merged.compare(merged));
        assertThrows(IllegalArgumentException.class, () -> base.compare(null));
    }

    @Test
    void antiEntropyConvergesAllCrdtTypesUnderRandomOps() {
        for (long seed = 1L; seed <= 5L; seed++) {
            ConvergenceKit.ScriptedRun run = ConvergenceKit.runScript(4, 12, seed);
            ConvergenceKit.antiEntropy(run, seed * 31L + 7L);
            assertTrue(ConvergenceKit.converged(run), "种子 " + seed + " 应全副本收敛");
        }
    }

    @Test
    void mergeInvariantsHoldCommutativeIdempotentAndShuffleSafe() {
        ConvergenceKit.ScriptedRun run = ConvergenceKit.runScript(3, 8, 99L);
        assertTrue(ConvergenceKit.commutativeMerge(run.twoPSets.get(0), run.twoPSets.get(1)));
        assertTrue(ConvergenceKit.idempotentMerge(run.counters.get(0)));

        YataText source = new YataText();
        for (char ch : "converge-me".toCharArray()) {
            source.append("s", ch);
        }
        source.insertAfter("t", source.lastId(), '!');
        List<YataText.ItemData> items = source.exportItems();
        for (long seed = 1L; seed <= 5L; seed++) {
            assertTrue(ConvergenceKit.yataConvergesUnderShuffle(items, seed),
                    "乱序拓扑重排导入应收敛到同一序（种子 " + seed + "）");
        }
        assertEquals("converge-me!", source.text());
    }

    @Test
    void gCounterStateRoundTrips() {
        GCounter counter = GCounter.growOnly();
        counter.increment("A", 2);
        counter.increment("B", 3);
        Map<String, Long> state = counter.state();
        GCounter mirror = GCounter.growOnly();
        for (Map.Entry<String, Long> e : state.entrySet()) {
            mirror.increment(e.getKey(), e.getValue());
        }
        assertEquals(counter.value(), mirror.value());
    }
}

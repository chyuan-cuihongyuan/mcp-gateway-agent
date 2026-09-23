package cn.chyuan.ai.domain.crdtkernel.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CRDT 端口组合管线测试（工单 0609 BT8）。
 * crdt-kernel.enabled 默认关（开启才改变行为）；
 * 与 configkernel 只读联动：配置快照可选 CRDT 无锁合并形态。
 */
class CrdtPortPipelineTest {

    @Test
    void configSnapshotsMergeLockFreeAndStabilize() {
        CrdtPort port = new CrdtPort.InMemoryCollaborator();
        Map<String, CrdtPort.ConfigEntry> instanceA = new LinkedHashMap<>();
        instanceA.put("ratelimit.qps", new CrdtPort.ConfigEntry("100", 100L, "instance-a"));
        instanceA.put("timeout.ms", new CrdtPort.ConfigEntry("5000", 90L, "instance-a"));
        Map<String, CrdtPort.ConfigEntry> instanceB = new LinkedHashMap<>();
        instanceB.put("ratelimit.qps", new CrdtPort.ConfigEntry("200", 100L, "instance-b"));
        instanceB.put("feature.flag", new CrdtPort.ConfigEntry("on", 120L, "instance-b"));

        CrdtPort.MergedConfig merged = port.mergeConfigs(instanceA, instanceB);
        assertTrue(merged.changed());
        assertEquals("200", merged.merged().get("ratelimit.qps").value(),
                "平局按副本 id：instance-b 胜");
        assertEquals("on", merged.merged().get("feature.flag").value());
        assertEquals("5000", merged.merged().get("timeout.ms").value());
        assertEquals(3, merged.merged().size());

        CrdtPort.MergedConfig mirrorOnA = port.mergeConfigs(instanceA, merged.merged());
        assertEquals(merged.merged(), mirrorOnA.merged(), "合并应交换收敛");
        assertTrue(port.isStable(merged.merged(), merged.merged()), "幂等：稳定态不再变化");

        Map<String, String> values = port.valueView(merged.merged());
        assertEquals("200", values.get("ratelimit.qps"));
        assertEquals("on", values.get("feature.flag"));
    }

    @Test
    void countersAndOrSetsMergeThroughPort() {
        CrdtPort port = new CrdtPort.InMemoryCollaborator();
        Map<String, Long> sideA = Map.of("a", 3L);
        Map<String, Long> sideB = Map.of("a", 2L, "b", 4L);
        assertEquals(7L, port.mergedCounter(sideA, sideB));
        assertThrows(IllegalArgumentException.class, () -> port.mergedCounter());
        assertThrows(IllegalArgumentException.class, () -> port.mergedCounter(Map.of("a", -1L)));

        OrSet local = new OrSet();
        local.add("tool:x", "A");
        OrSet remote = new OrSet();
        remote.add("tool:x", "B");
        remote.add("tool:y", "B");
        List<String> live = port.mergeOrSet(local, remote);
        assertEquals(List.of("tool:x", "tool:y"), live);
        remote.remove("tool:x");
        assertEquals(List.of("tool:x", "tool:y"), port.mergeOrSet(local, remote).stream().sorted().toList(),
                "远端只删自己观察的 B 标签，A 标签仍存活");
        local.remove("tool:x");
        assertEquals(List.of("tool:y"), port.mergeOrSet(local, remote).stream().sorted().toList(),
                "本地观察到 A、B 两标签全删后元素死亡");
    }

    @Test
    void textMergesThroughPortAndRejectsNulls() {
        CrdtPort port = new CrdtPort.InMemoryCollaborator();
        YataText local = new YataText();
        local.append("a", '你');
        local.append("a", '好');
        YataText remote = new YataText();
        remote.append("a", '你');
        remote.append("a", '好');
        remote.insertAfter("b", remote.lastId(), '！');

        String merged = port.mergeText(local, remote.exportItems());
        assertEquals("你好！", merged);

        assertThrows(IllegalArgumentException.class, () -> port.mergeText(null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> port.mergeConfigs(null, Map.of()));
    }

    @Test
    void threeInstanceGossipReachesFixedPoint() {
        CrdtPort port = new CrdtPort.InMemoryCollaborator();
        Map<String, CrdtPort.ConfigEntry> a = new LinkedHashMap<>();
        a.put("k", new CrdtPort.ConfigEntry("a-v", 10L, "i-a"));
        Map<String, CrdtPort.ConfigEntry> b = new LinkedHashMap<>();
        b.put("k", new CrdtPort.ConfigEntry("b-v", 12L, "i-b"));
        Map<String, CrdtPort.ConfigEntry> c = new LinkedHashMap<>();
        c.put("k", new CrdtPort.ConfigEntry("c-v", 12L, "i-c"));

        Map<String, CrdtPort.ConfigEntry> ab = port.mergeConfigs(a, b).merged();
        Map<String, CrdtPort.ConfigEntry> abc = port.mergeConfigs(ab, c).merged();
        assertEquals("c-v", abc.get("k").value(), "时间戳平局副本定序：i-c 最大");

        Map<String, CrdtPort.ConfigEntry> cb = port.mergeConfigs(c, b).merged();
        Map<String, CrdtPort.ConfigEntry> cba = port.mergeConfigs(cb, a).merged();
        assertEquals(abc, cba, "三向合并与次序无关（收敛）");
        assertTrue(port.isStable(abc, abc));
    }
}

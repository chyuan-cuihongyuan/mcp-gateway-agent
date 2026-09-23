package cn.chyuan.ai.domain.crdtkernel.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CRDT 端口+组合管线（工单 0609 BT8）。
 * CrdtPort（CRDT 状态导出/远程状态合并应用/收敛校验）组合管线：
 * 多实例配置 map 以 LWW-Register 按 key 无锁合并→收敛断言；
 * 与 configkernel 只读联动（配置快照可选 CRDT 合并形态，
 * 泛型入参不 import configkernel，不改 configkernel 任何类）/
 * crdt-kernel.enabled 默认关（开启才改变行为）。
 */
public interface CrdtPort {

    /** LWW 条目（联动形态：configcenter 快照的一 key 一值带时间戳） */
    record ConfigEntry(String value, long timestamp, String replica) {
    }

    /** 配置无锁合并结果 */
    record MergedConfig(Map<String, ConfigEntry> merged, boolean changed) {
    }

    /** 计数器联动：多副本计数收敛 */
    long mergedCounter(Map<String, Long>... sides);

    /** 配置 map 无锁合并（逐 key LWW；确定性平局副本 id 定序） */
    MergedConfig mergeConfigs(Map<String, ConfigEntry> local, Map<String, ConfigEntry> remote);

    /** 合并结果转纯值视图（联动出口形态） */
    Map<String, String> valueView(Map<String, ConfigEntry> merged);

    /** YATA 文本合并：把远端条目整合进本地（拓扑序重试），返回收敛文本 */
    String mergeText(YataText local, List<YataText.ItemData> remoteItems);

    /** OR-Set 合并 + 存活视图 */
    List<String> mergeOrSet(OrSet local, OrSet remote);

    /** 收敛校验（合并幂等：再次合并 changed=false） */
    boolean isStable(Map<String, ConfigEntry> local, Map<String, ConfigEntry> merged);

    /** 内存假实现：全 CRDT 类型组合管线 */
    class InMemoryCollaborator implements CrdtPort {

        @SafeVarargs
        @Override
        public final long mergedCounter(Map<String, Long>... sides) {
            if (sides == null || sides.length == 0) {
                throw new IllegalArgumentException("计数器侧不得为空");
            }
            GCounter total = GCounter.growOnly();
            for (Map<String, Long> side : sides) {
                if (side == null) {
                    throw new IllegalArgumentException("计数器侧不得为 null");
                }
                GCounter one = GCounter.growOnly();
                for (Map.Entry<String, Long> e : side.entrySet()) {
                    if (e.getValue() < 0) {
                        throw new IllegalArgumentException("副本计数不得为负");
                    }
                    for (long i = 0; i < e.getValue(); i++) {
                        one.increment(e.getKey(), 1);
                    }
                }
                total.merge(one);
            }
            return total.value();
        }

        @Override
        public synchronized MergedConfig mergeConfigs(Map<String, ConfigEntry> local,
                                                      Map<String, ConfigEntry> remote) {
            if (local == null || remote == null) {
                throw new IllegalArgumentException("合并两侧不得为 null");
            }
            Map<String, Registers.LwwEntry> localEntries = new LinkedHashMap<>();
            for (Map.Entry<String, ConfigEntry> e : local.entrySet()) {
                localEntries.put(e.getKey(),
                        new Registers.LwwEntry(e.getValue().value(), e.getValue().timestamp(), e.getValue().replica()));
            }
            Map<String, Registers.LwwEntry> remoteEntries = new LinkedHashMap<>();
            for (Map.Entry<String, ConfigEntry> e : remote.entrySet()) {
                remoteEntries.put(e.getKey(),
                        new Registers.LwwEntry(e.getValue().value(), e.getValue().timestamp(), e.getValue().replica()));
            }
            Map<String, Registers.LwwEntry> merged = Registers.mergeConfigMaps(localEntries, remoteEntries);
            Map<String, ConfigEntry> out = new LinkedHashMap<>();
            merged.forEach((k, v) -> out.put(k, new ConfigEntry(v.value(), v.timestamp(), v.replica())));
            boolean changed = !out.equals(local);
            return new MergedConfig(out, changed);
        }

        @Override
        public synchronized Map<String, String> valueView(Map<String, ConfigEntry> merged) {
            Map<String, String> out = new LinkedHashMap<>();
            merged.forEach((k, v) -> out.put(k, v.value()));
            return out;
        }

        @Override
        public synchronized String mergeText(YataText local, List<YataText.ItemData> remoteItems) {
            if (local == null || remoteItems == null) {
                throw new IllegalArgumentException("合并对象不得为 null");
            }
            ConvergenceKit.exchangeYataFromList(local, remoteItems);
            return local.text();
        }

        @Override
        public synchronized List<String> mergeOrSet(OrSet local, OrSet remote) {
            if (local == null || remote == null) {
                throw new IllegalArgumentException("合并对象不得为 null");
            }
            local.merge(remote);
            return List.copyOf(new java.util.ArrayList<>(local.live()));
        }

        @Override
        public synchronized boolean isStable(Map<String, ConfigEntry> local, Map<String, ConfigEntry> merged) {
            return !mergeConfigs(local, merged).changed();
        }    }
}

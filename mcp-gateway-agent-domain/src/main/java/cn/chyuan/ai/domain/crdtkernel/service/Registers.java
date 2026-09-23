package cn.chyuan.ai.domain.crdtkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LWW-Register 与 MV-Register（工单 0603 BT2，yjs 寄存器思想）。
 * LWW：时间戳+副本 id 比较（平局按副本 id 字典序定序，确定性）；
 * MV：并发写多值保留（兄弟值集合）/再写清空兄弟/读返回并发多值。
 */
public final class Registers {

    private Registers() {
    }

    /** LWW 条目 */
    public record LwwEntry(String value, long timestamp, String replica) {

        public LwwEntry {
            if (replica == null || replica.isBlank()) {
                throw new IllegalArgumentException("副本标识不得为空");
            }
        }

        /** 定序键：时间戳优先，平局按副本 id（确定性） */
        boolean dominates(LwwEntry other) {
            if (timestamp != other.timestamp) {
                return timestamp > other.timestamp;
            }
            return replica.compareTo(other.replica) > 0;
        }

        /** 因果取代：同副本后写覆盖前写（跨副本同刻度视为并发，MV 口径） */
        boolean supersedes(LwwEntry other) {
            return replica.equals(other.replica) && timestamp >= other.timestamp && !equals(other);
        }
    }

    /** LWW-Register：最后写入者胜 */
    public static final class LwwRegister {

        private LwwEntry current;

        public LwwRegister(String initialValue, long timestamp, String replica) {
            this.current = new LwwEntry(initialValue, timestamp, replica);
        }

        /** 本地写：仅当定序键更大才生效 */
        public synchronized void write(String value, long timestamp, String replica) {
            LwwEntry candidate = new LwwEntry(value, timestamp, replica);
            if (candidate.dominates(current)) {
                current = candidate;
            }
        }

        /** 合并：取定序键更大者（交换律） */
        public synchronized void merge(LwwRegister other) {
            if (other == null) {
                throw new IllegalArgumentException("合并对象不得为 null");
            }
            if (other.current.dominates(current)) {
                current = other.current;
            }
        }

        public synchronized String value() {
            return current.value();
        }

        public synchronized LwwEntry state() {
            return current;
        }
    }

    /** MV-Register：并发多值兄弟集合 */
    public static final class MvRegister {

        private final List<LwwEntry> siblings = new ArrayList<>();

        public MvRegister(String initialValue, long timestamp, String replica) {
            siblings.add(new LwwEntry(initialValue, timestamp, replica));
        }

        /** 本地写：清空兄弟只留新值 */
        public synchronized void write(String value, long timestamp, String replica) {
            siblings.clear();
            siblings.add(new LwwEntry(value, timestamp, replica));
        }

        /** 合并：并集后剔除被支配兄弟（保留极大元，交换律） */
        public synchronized void merge(MvRegister other) {
            if (other == null) {
                throw new IllegalArgumentException("合并对象不得为 null");
            }
            List<LwwEntry> union = new ArrayList<>(siblings);
            for (LwwEntry e : other.siblings) {
                if (!union.contains(e)) {
                    union.add(e);
                }
            }
            siblings.clear();
            for (LwwEntry candidate : union) {
                boolean dominated = false;
                for (LwwEntry otherEntry : union) {
                    if (otherEntry != candidate && otherEntry.supersedes(candidate)) {
                        dominated = true;
                        break;
                    }
                }
                if (!dominated) {
                    siblings.add(candidate);
                }
            }
        }

        /** 读：并发多值（规范化定序：时间戳/副本/值，与合并方向无关） */
        public synchronized List<String> values() {
            List<String> out = new ArrayList<>();
            for (LwwEntry e : canonical()) {
                out.add(e.value());
            }
            return out;
        }

        public synchronized List<LwwEntry> state() {
            return canonical();
        }

        private List<LwwEntry> canonical() {
            List<LwwEntry> sorted = new ArrayList<>(siblings);
            sorted.sort(java.util.Comparator.comparingLong(LwwEntry::timestamp)
                    .thenComparing(LwwEntry::replica)
                    .thenComparing(LwwEntry::value));
            return sorted;
        }
    }

    /** 配置 map 的 LWW 无锁合并（configkernel 联动形态：逐 key LwwRegister） */
    public static Map<String, LwwEntry> mergeConfigMaps(Map<String, LwwEntry> local,
                                                        Map<String, LwwEntry> remote) {
        if (local == null || remote == null) {
            throw new IllegalArgumentException("合并两侧不得为 null");
        }
        Map<String, LwwEntry> merged = new LinkedHashMap<>(local);
        for (Map.Entry<String, LwwEntry> e : remote.entrySet()) {
            LwwEntry mine = merged.get(e.getKey());
            if (mine == null || e.getValue().dominates(mine)) {
                merged.put(e.getKey(), e.getValue());
            }
        }
        return merged;
    }
}

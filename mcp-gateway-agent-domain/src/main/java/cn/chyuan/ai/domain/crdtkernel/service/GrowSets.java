package cn.chyuan.ai.domain.crdtkernel.service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * G-Set 与 2P-Set（工单 0604 BT3，yjs 集合思想）。
 * G-Set 只增并集合并/删除拒绝；
 * 2P-Set 添加集 A+观察删除集 R/contains=A∧¬R/删除后不可再添加
 * （墓碑生效，重复添加被忽略）/两侧并集合并语义。
 */
public final class GrowSets {

    private GrowSets() {
    }

    /** G-Set：只增集合 */
    public static final class GSet {

        private final Set<String> elements = new LinkedHashSet<>();

        /** 添加（已存在幂等） */
        public synchronized void add(String element) {
            if (element == null) {
                throw new IllegalArgumentException("元素不得为 null");
            }
            elements.add(element);
        }

        /** 删除拒绝（G-Set 语义） */
        public synchronized void remove(String element) {
            throw new IllegalArgumentException("G-Set 只增：删除拒绝");
        }

        /** 合并：并集 */
        public synchronized void merge(GSet other) {
            if (other == null) {
                throw new IllegalArgumentException("合并对象不得为 null");
            }
            elements.addAll(other.elements);
        }

        public synchronized boolean contains(String element) {
            return elements.contains(element);
        }

        public synchronized Set<String> elements() {
            return new LinkedHashSet<>(elements);
        }

        public synchronized int size() {
            return elements.size();
        }
    }

    /** 2P-Set：添加集 + 观察删除集 */
    public static final class TwoPSet {

        private final Set<String> added = new LinkedHashSet<>();
        private final Set<String> removed = new LinkedHashSet<>();

        /** 添加：未墓碑才入添加集（墓碑后重复添加被忽略） */
        public synchronized void add(String element) {
            if (element == null) {
                throw new IllegalArgumentException("元素不得为 null");
            }
            if (!removed.contains(element)) {
                added.add(element);
            }
        }

        /** 删除：观察当前存活 → 入墓碑集 */
        public synchronized void remove(String element) {
            if (element == null) {
                throw new IllegalArgumentException("元素不得为 null");
            }
            if (added.contains(element)) {
                removed.add(element);
            }
        }

        /** 合并：两侧添加并集 + 墓碑并集 */
        public synchronized void merge(TwoPSet other) {
            if (other == null) {
                throw new IllegalArgumentException("合并对象不得为 null");
            }
            added.addAll(other.added);
            removed.addAll(other.removed);
        }

        public synchronized boolean contains(String element) {
            return added.contains(element) && !removed.contains(element);
        }

        /** 存活元素（添加且未删） */
        public synchronized Set<String> live() {
            Set<String> out = new LinkedHashSet<>();
            for (String e : added) {
                if (!removed.contains(e)) {
                    out.add(e);
                }
            }
            return out;
        }

        /** 状态导出（反熵联动面） */
        public synchronized Map<String, Set<String>> state() {
            Map<String, Set<String>> out = new LinkedHashMap<>();
            out.put("added", new LinkedHashSet<>(added));
            out.put("removed", new LinkedHashSet<>(removed));
            return out;
        }
    }
}

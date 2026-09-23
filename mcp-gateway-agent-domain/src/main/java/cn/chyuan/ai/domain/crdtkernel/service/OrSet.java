package cn.chyuan.ai.domain.crdtkernel.service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * OR-Set 观察删除集（工单 0605 BT4，yjs ORSet 思想）。
 * add 打唯一标签（副本 id+序号）/remove 观察当时全部标签进墓碑集/
 * 并发 add+remove（remove 后再 add 存活）/元素查询返回存活标签/
 * 合并=条目并集+墓碑并集（Observed-Remove 语义）。
 */
public final class OrSet {

    /** 标签：副本+序号唯一 */
    public record Tag(String replica, long seq) {
    }

    private final Map<String, Set<Tag>> entries = new LinkedHashMap<>();
    private final Set<Tag> tombstones = new LinkedHashSet<>();
    private final Map<String, Long> seqByReplica = new LinkedHashMap<>();

    /** 添加：打唯一标签 */
    public synchronized void add(String element, String replica) {
        if (element == null || replica == null || replica.isBlank()) {
            throw new IllegalArgumentException("元素与副本标识不得为空");
        }
        long seq = seqByReplica.merge(replica, 1L, Long::sum);
        entries.computeIfAbsent(element, k -> new LinkedHashSet<>()).add(new Tag(replica, seq));
    }

    /** 移除：观察当时全部存活标签 → 墓碑 */
    public synchronized void remove(String element) {
        if (element == null) {
            throw new IllegalArgumentException("元素不得为 null");
        }
        Set<Tag> tags = entries.get(element);
        if (tags != null) {
            for (Tag tag : new LinkedHashSet<>(tags)) {
                if (!tombstones.contains(tag)) {
                    tombstones.add(tag);
                }
            }
        }
    }

    /** 删除后重加：新标签存活（并发加删语义） */
    public synchronized boolean contains(String element) {
        Set<Tag> tags = entries.get(element);
        if (tags == null) {
            return false;
        }
        for (Tag tag : tags) {
            if (!tombstones.contains(tag)) {
                return true;
            }
        }
        return false;
    }

    /** 元素存活标签 */
    public synchronized Set<Tag> liveTags(String element) {
        Set<Tag> out = new LinkedHashSet<>();
        Set<Tag> tags = entries.get(element);
        if (tags != null) {
            for (Tag tag : tags) {
                if (!tombstones.contains(tag)) {
                    out.add(tag);
                }
            }
        }
        return out;
    }

    /** 存活元素集 */
    public synchronized Set<String> live() {
        Set<String> out = new LinkedHashSet<>();
        for (String element : entries.keySet()) {
            if (contains(element)) {
                out.add(element);
            }
        }
        return out;
    }

    /** 合并：条目标签并集 + 墓碑并集 */
    public synchronized void merge(OrSet other) {
        if (other == null) {
            throw new IllegalArgumentException("合并对象不得为 null");
        }
        for (Map.Entry<String, Set<Tag>> e : other.entries.entrySet()) {
            entries.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).addAll(e.getValue());
        }
        tombstones.addAll(other.tombstones);
        for (Map.Entry<String, Long> e : other.seqByReplica.entrySet()) {
            seqByReplica.merge(e.getKey(), e.getValue(), Math::max);
        }
    }

    /** 状态导出（反熵联动面） */
    public synchronized Map<String, Set<Tag>> state() {
        Map<String, Set<Tag>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Set<Tag>> e : entries.entrySet()) {
            out.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
        }
        return out;
    }

    public synchronized Set<Tag> tombstonesView() {
        return new LinkedHashSet<>(tombstones);
    }
}

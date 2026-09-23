package cn.chyuan.ai.domain.crdtkernel.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 版本向量（工单 0607 BT6，yjs 向量时钟思想）。
 * 逐副本计数/本地操作自增/因果偏序比较（支配/被支配/并发三分）/
 * 缺失前驱识别（对方计数>本地即有未见操作）/向量合并逐分量取 max。
 */
public final class VersionVector {

    /** 偏序关系 */
    public enum Relation {
        DOMINATES, DOMINATED_BY, CONCURRENT, EQUAL
    }

    private final Map<String, Long> counts = new TreeMap<>();

    /** 本地操作：自增该副本计数 */
    public synchronized long tick(String replica) {
        if (replica == null || replica.isBlank()) {
            throw new IllegalArgumentException("副本标识不得为空");
        }
        return counts.merge(replica, 1L, Long::sum);
    }

    /** 偏序比较 */
    public synchronized Relation compare(VersionVector other) {
        if (other == null) {
            throw new IllegalArgumentException("比较对象不得为 null");
        }
        boolean iDominate = false;
        boolean dominated = false;
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            long otherCount = other.counts.getOrDefault(e.getKey(), 0L);
            if (e.getValue() > otherCount) {
                iDominate = true;
            } else if (e.getValue() < otherCount) {
                dominated = true;
            }
        }
        for (Map.Entry<String, Long> e : other.counts.entrySet()) {
            long myCount = counts.getOrDefault(e.getKey(), 0L);
            if (e.getValue() > myCount) {
                dominated = true;
            } else if (e.getValue() < myCount) {
                iDominate = true;
            }
        }
        if (iDominate && dominated) {
            return Relation.CONCURRENT;
        }
        if (iDominate) {
            return Relation.DOMINATES;
        }
        if (dominated) {
            return Relation.DOMINATED_BY;
        }
        return Relation.EQUAL;
    }

    /** 缺失前驱：对方有本地未见的操作（对方支配本地才无缺失） */
    public synchronized boolean hasMissingPrecedents(VersionVector other) {
        return compare(other) == Relation.DOMINATED_BY || compare(other) == Relation.CONCURRENT;
    }

    /** 合并：逐分量取 max（单调） */
    public synchronized void merge(VersionVector other) {
        if (other == null) {
            throw new IllegalArgumentException("合并对象不得为 null");
        }
        for (Map.Entry<String, Long> e : other.counts.entrySet()) {
            counts.merge(e.getKey(), e.getValue(), Math::max);
        }
    }

    /** 状态导出（只读快照） */
    public synchronized Map<String, Long> state() {
        return new LinkedHashMap<>(counts);
    }

    /** 从状态快照恢复 */
    public static VersionVector fromState(Map<String, Long> state) {
        if (state == null) {
            throw new IllegalArgumentException("状态不得为 null");
        }
        VersionVector vector = new VersionVector();
        vector.counts.putAll(state);
        return vector;
    }
}

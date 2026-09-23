package cn.chyuan.ai.domain.crdtkernel.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * G-Counter 与 PN-Counter（工单 0602 BT1，yjs CRDT 基础结构思想）。
 * G-Counter 分副本单调增/合并取逐副本 max/value=逐副本之和/递减拒绝；
 * PN-Counter 增计数器对+减计数器对/value=P−N（非负约束）。
 */
public final class GCounter {

    private final Map<String, Long> counts = new TreeMap<>();
    private final boolean allowDecrement;

    private GCounter(boolean allowDecrement) {
        this.allowDecrement = allowDecrement;
    }

    /** G-Counter（只增） */
    public static GCounter growOnly() {
        return new GCounter(false);
    }

    /** PN-Counter 的半边（allowDecrement 时 n 可负） */
    public static GCounter pnSide() {
        return new GCounter(true);
    }

    /** 本副本递增（n 须为正；PN 半边允许负） */
    public synchronized void increment(String replica, long n) {
        if (replica == null || replica.isBlank()) {
            throw new IllegalArgumentException("副本标识不得为空");
        }
        if (n <= 0 && !allowDecrement) {
            throw new IllegalArgumentException("G-Counter 只增：递减拒绝");
        }
        if (n == 0) {
            throw new IllegalArgumentException("增量不得为零");
        }
        counts.merge(replica, n, Long::sum);
        if (counts.get(replica) < 0) {
            throw new IllegalArgumentException("副本计数不得为负");
        }
    }

    /** 合并：逐副本取 max（单调收敛） */
    public synchronized void merge(GCounter other) {
        if (other == null) {
            throw new IllegalArgumentException("合并对象不得为 null");
        }
        for (Map.Entry<String, Long> e : other.counts.entrySet()) {
            counts.merge(e.getKey(), e.getValue(), Math::max);
        }
    }

    /** 值：逐副本之和 */
    public synchronized long value() {
        long sum = 0;
        for (long c : counts.values()) {
            sum += c;
        }
        return sum;
    }

    /** 状态导出（只读快照，反熵联动面） */
    public synchronized Map<String, Long> state() {
        return new LinkedHashMap<>(counts);
    }

    /** PN-Counter：P−N 组合值 */
    public static long pnValue(GCounter positive, GCounter negative) {
        if (positive == null || negative == null) {
            throw new IllegalArgumentException("PN 两半不得为 null");
        }
        return positive.value() - negative.value();
    }
}

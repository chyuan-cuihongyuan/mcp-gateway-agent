package cn.chyuan.ai.domain.windowkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 键控状态（工单 0562 BO6，flink keyed state 思想）。
 * 按 key 分区窗口状态互不可见/状态 TTL 过期清理（时钟端口）/
 * 键基数与状态规模统计/清理后索引一致性。window-kernel.enabled 默认关。
 */
public final class KeyedWindowState {

    /** 窗口状态：窗口内累计值与计数 */
    public static final class WindowState {
        private double sum;
        private int count;

        public void add(double value) {
            sum += value;
            count++;
        }

        public double sum() {
            return sum;
        }

        public int count() {
            return count;
        }
    }

    private record Entry(WindowState state, long lastTouchMillis) {
    }

    private final long ttlMillis;
    private final java.util.function.LongSupplier clock;
    private final Map<String, TreeMap<Long, Entry>> byKey = new LinkedHashMap<>();
    private long evictedStates;

    public KeyedWindowState(long ttlMillis, java.util.function.LongSupplier clock) {
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("TTL 须为正");
        }
        if (clock == null) {
            throw new IllegalArgumentException("时钟端口不得为 null");
        }
        this.ttlMillis = ttlMillis;
        this.clock = clock;
    }

    /** 键+窗口粒度累计（互不可见：不同键/不同窗独立状态） */
    public void add(String key, WindowAssigner.Window window, double value) {
        evictExpired();
        byKey.computeIfAbsent(key, k -> new TreeMap<>())
                .computeIfAbsent(window.start(), w -> new Entry(new WindowState(), clock.getAsLong()))
                .state().add(value);
    }

    /** 读取键窗状态（缺失零值；过期视为缺失） */
    public WindowState get(String key, WindowAssigner.Window window) {
        evictExpired();
        TreeMap<Long, Entry> windows = byKey.get(key);
        if (windows == null) {
            return new WindowState();
        }
        Entry entry = windows.get(window.start());
        return entry == null ? new WindowState() : entry.state();
    }

    /** TTL 过期清理（lastTouch 超龄逐出，键空桶回收；返回本次逐出数） */
    public int evictExpired() {
        long now = clock.getAsLong();
        int[] evicted = {0};
        List<String> emptyKeys = new ArrayList<>();
        for (Map.Entry<String, TreeMap<Long, Entry>> entry : byKey.entrySet()) {
            entry.getValue().values().removeIf(state -> {
                boolean expired = now - state.lastTouchMillis() > ttlMillis;
                if (expired) {
                    evicted[0]++;
                    evictedStates++;
                }
                return expired;
            });
            if (entry.getValue().isEmpty()) {
                emptyKeys.add(entry.getKey());
            }
        }
        for (String key : emptyKeys) {
            byKey.remove(key);
        }
        return evicted[0];
    }

    /** 键基数 */
    public int keyCount() {
        return byKey.size();
    }

    /** 状态规模（键窗状态总数） */
    public int stateCount() {
        int total = 0;
        for (TreeMap<Long, Entry> windows : byKey.values()) {
            total += windows.size();
        }
        return total;
    }

    public long evictedStates() {
        return evictedStates;
    }
}

package cn.chyuan.ai.domain.windowkernel.service;

import java.util.List;

/**
 * 窗口区间与归属判定（工单 0559 BO3，flink 窗口分配思想）。
 * 滚动窗长对齐分窗（epoch 向下取整）/滑动窗长+步长多窗归属/
 * 窗口区间 [start,end) 表示/边界事件归属断言。会话窗口见 SessionWindows。
 */
public final class WindowAssigner {

    /** 窗口区间 [start,end) */
    public record Window(long start, long end) {
        public boolean contains(long eventTime) {
            return eventTime >= start && eventTime < end;
        }
    }

    private final long windowSizeMillis;
    private final long slideMillis;

    /** 滚动窗口 slide=0 视为窗长；滑动窗口 slide ∈ (0, 窗长] */
    public WindowAssigner(long windowSizeMillis, long slideMillis) {
        if (windowSizeMillis <= 0) {
            throw new IllegalArgumentException("窗长须为正");
        }
        if (slideMillis < 0 || slideMillis > windowSizeMillis) {
            throw new IllegalArgumentException("步长须在 [0, 窗长]");
        }
        this.windowSizeMillis = windowSizeMillis;
        this.slideMillis = slideMillis == 0 ? windowSizeMillis : slideMillis;
    }

    public long windowSize() {
        return windowSizeMillis;
    }

    public long slide() {
        return slideMillis;
    }

    /** 滚动窗口归属（唯一）：start = floor(t/窗长)×窗长 */
    public Window assignTumbling(long eventTime) {
        long start = Math.floorDiv(eventTime, windowSizeMillis) * windowSizeMillis;
        return new Window(start, start + windowSizeMillis);
    }

    /** 滑动窗口归属（多窗）：覆盖事件时间的全部 [start,end) */
    public List<Window> assignSliding(long eventTime) {
        long lastStart = Math.floorDiv(eventTime, slideMillis) * slideMillis;
        List<Window> windows = new java.util.ArrayList<>();
        long start = lastStart;
        while (start > eventTime - windowSizeMillis) {
            windows.add(new Window(start, start + windowSizeMillis));
            start -= slideMillis;
        }
        java.util.Collections.reverse(windows);
        return List.copyOf(windows);
    }

    /** 批量归属（滚动，键无关） */
    public java.util.Map<Long, Integer> countsByTumblingWindow(List<WatermarkTracker.Event> events) {
        java.util.Map<Long, Integer> counts = new java.util.TreeMap<>();
        for (WatermarkTracker.Event event : events) {
            Window window = assignTumbling(event.eventTime());
            counts.merge(window.start(), 1, Integer::sum);
        }
        return java.util.Collections.unmodifiableSortedMap(new java.util.TreeMap<>(counts));
    }
}

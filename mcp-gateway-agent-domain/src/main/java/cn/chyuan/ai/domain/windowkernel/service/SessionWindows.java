package cn.chyuan.ai.domain.windowkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * 会话窗口（工单 0560 BO4，flink session window 思想）。
 * 事件按 gap 间隔建窗/相邻窗口间隔≤gap 合并/合并后区间并集/
 * 边界计算确定性（间隔==gap 视为同会话）。纯函数：事件集进，窗口集出。
 */
public final class SessionWindows {

    private final long gapMillis;

    public SessionWindows(long gapMillis) {
        if (gapMillis < 0) {
            throw new IllegalArgumentException("gap 须非负");
        }
        this.gapMillis = gapMillis;
    }

    public long gap() {
        return gapMillis;
    }

    /** 单键会话窗口集：按事件时间排序后线性扫描合并（间隔≤gap 并窗） */
    public List<WindowAssigner.Window> assign(List<WatermarkTracker.Event> events) {
        if (events.isEmpty()) {
            return List.of();
        }
        List<Long> times = new ArrayList<>();
        for (WatermarkTracker.Event event : events) {
            times.add(event.eventTime());
        }
        java.util.Collections.sort(times);
        List<WindowAssigner.Window> windows = new ArrayList<>();
        long start = times.get(0);
        long end = start + 1;
        for (Long time : times) {
            if (time - end <= gapMillis) {
                end = Math.max(end, time + 1);
            } else {
                windows.add(new WindowAssigner.Window(start, end));
                start = time;
                end = time + 1;
            }
        }
        windows.add(new WindowAssigner.Window(start, end));
        return List.copyOf(windows);
    }

    /** 多键会话窗口：键 → 窗口集 */
    public TreeMap<String, List<WindowAssigner.Window>> assignByKey(List<WatermarkTracker.Event> events) {
        TreeMap<String, List<WatermarkTracker.Event>> byKey = new TreeMap<>();
        for (WatermarkTracker.Event event : events) {
            byKey.computeIfAbsent(event.key(), k -> new ArrayList<>()).add(event);
        }
        TreeMap<String, List<WindowAssigner.Window>> out = new TreeMap<>();
        for (java.util.Map.Entry<String, List<WatermarkTracker.Event>> entry : byKey.entrySet()) {
            out.put(entry.getKey(), assign(entry.getValue()));
        }
        return out;
    }

    /** 窗口区间并集合法性（合并后无重叠且升序） */
    public static boolean disjointAscending(List<WindowAssigner.Window> windows) {
        for (int i = 1; i < windows.size(); i++) {
            if (windows.get(i).start() < windows.get(i - 1).end()) {
                return false;
            }
        }
        return true;
    }
}

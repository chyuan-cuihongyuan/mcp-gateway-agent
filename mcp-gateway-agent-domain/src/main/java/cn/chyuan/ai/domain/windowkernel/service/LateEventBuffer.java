package cn.chyuan.ai.domain.windowkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 乱序缓冲与迟到处理（工单 0558 BO2，flink allowed lateness 思想）。
 * 窗口保持至 watermark 越界才关闭/关闭前乱序事件仍归属旧窗/
 * 关闭后迟到事件旁路输出并计数/allowed lateness 二次触发简化（延迟关闭窗尾）。
 */
public final class LateEventBuffer {

    /** 处理结果：命中窗口/旁路输出 */
    public record Outcome(boolean accepted, WindowAssigner.Window window, boolean sideOutput) {
    }

    private final long allowedLatenessMillis;
    private final java.util.function.LongSupplier watermarkPort;
    private final List<WindowAssigner.Window> closedWindows = new ArrayList<>();
    private long lateCount;

    public LateEventBuffer(long allowedLatenessMillis, java.util.function.LongSupplier watermarkPort) {
        if (allowedLatenessMillis < 0) {
            throw new IllegalArgumentException("allowed lateness 须非负");
        }
        this.allowedLatenessMillis = allowedLatenessMillis;
        this.watermarkPort = watermarkPort;
    }

    public long watermark() {
        return watermarkPort.getAsLong();
    }

    /**
     * 事件投递判定：watermark < 窗尾-允许迟到 → 正常入窗；
     * 窗尾-允许迟到 ≤ watermark < 窗尾 → 宽限入窗（二次触发窗口）；
     * watermark ≥ 窗尾 → 窗口已关闭，迟到旁路输出。
     */
    public Outcome onEvent(WatermarkTracker.Event event, WindowAssigner.Window window) {
        long wm = watermark();
        long graceEnd = window.end();
        long hardEnd = window.end() - allowedLatenessMillis;
        if (wm < hardEnd) {
            return new Outcome(true, window, false);
        }
        if (wm < graceEnd) {
            return new Outcome(true, window, false);
        }
        lateCount++;
        return new Outcome(false, window, true);
    }

    /** 关闭窗口登记（watermark 越过窗尾+宽限后） */
    public boolean closeIfDue(WindowAssigner.Window window) {
        if (watermark() >= window.end() - allowedLatenessMillis && !closedWindows.contains(window)) {
            closedWindows.add(window);
            return true;
        }
        return false;
    }

    public List<WindowAssigner.Window> closedWindows() {
        return List.copyOf(closedWindows);
    }

    public long lateCount() {
        return lateCount;
    }

    /** 旁路输出收集（迟到事件按输入序） */
    public List<WatermarkTracker.Event> sideOutput(List<WatermarkTracker.Event> events,
            java.util.function.Function<WatermarkTracker.Event, WindowAssigner.Window> assigner) {
        List<WatermarkTracker.Event> late = new ArrayList<>();
        for (WatermarkTracker.Event event : events) {
            Outcome outcome = onEvent(event, assigner.apply(event));
            if (outcome.sideOutput()) {
                late.add(event);
            }
        }
        return List.copyOf(late);
    }

    /** 键控视图占位（组合管线用：键 → 判定） */
    public Map<String, Boolean> acceptanceByKey(List<WatermarkTracker.Event> events,
            java.util.function.Function<WatermarkTracker.Event, WindowAssigner.Window> assigner) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (WatermarkTracker.Event event : events) {
            out.put(event.key(), onEvent(event, assigner.apply(event)).accepted());
        }
        return java.util.Collections.unmodifiableMap(out);
    }
}

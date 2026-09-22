package cn.chyuan.ai.domain.windowkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 窗口端口+组合管线（工单 0564 BO8）。
 * WindowPort（事件流→窗口结果流确定性重放）+组合管线（键控→滚动窗→聚合→触发输出）+
 * 与 msgkernel 消息流只读联动（消息时间戳喂窗口，不改 msgkernel）/
 * window-kernel.enabled 默认关（开启才改变行为）。
 */
public interface WindowPort {

    /** 窗口结果行（键/窗起点/窗内计数/累计值/触发原因） */
    record WindowResult(String key, long windowStart, int count, double sum, String reason) {
    }

    /**
     * 组合管线：事件流按滚动窗分配+键控聚合，watermark 越过窗尾触发输出，
     * 迟到事件旁路计数。同输入同输出（确定性重放）。
     */
    List<WindowResult> process(List<WatermarkTracker.Event> events, WindowAssigner assigner,
            WatermarkTracker tracker);

    /** 迟到旁路输出 */
    List<WatermarkTracker.Event> sideOutput();

    /** 内存假实现 */
    class InMemoryWindowEngine implements WindowPort {

        private final List<WatermarkTracker.Event> late = new ArrayList<>();

        @Override
        public List<WindowResult> process(List<WatermarkTracker.Event> events, WindowAssigner assigner,
                WatermarkTracker tracker) {
            if (assigner.slide() != assigner.windowSize()) {
                throw new IllegalArgumentException("组合管线要求滚动窗（滑动窗见 WindowAssigner）");
            }
            Map<String, TreeMap<Long, WindowStateAcc>> keyed = new LinkedHashMap<>();
            for (WatermarkTracker.Event event : events) {
                tracker.observe(event.eventTime());
                if (tracker.isLate(event.eventTime())) {
                    late.add(event);
                    continue;
                }
                WindowAssigner.Window window = assigner.assignTumbling(event.eventTime());
                keyed.computeIfAbsent(event.key(), k -> new TreeMap<>())
                        .computeIfAbsent(window.start(), w -> new WindowStateAcc())
                        .add(event.value());
            }
            List<WindowResult> results = new ArrayList<>();
            for (Map.Entry<String, TreeMap<Long, WindowStateAcc>> entry : keyed.entrySet()) {
                for (Map.Entry<Long, WindowStateAcc> window : entry.getValue().entrySet()) {
                    WindowStateAcc acc = window.getValue();
                    String reason = tracker.firesWindowEnd(window.getKey() + assigner.windowSize())
                            ? "WATERMARK" : "BUFFERED";
                    results.add(new WindowResult(entry.getKey(), window.getKey(), acc.count, acc.sum, reason));
                }
            }
            return List.copyOf(results);
        }

        @Override
        public List<WatermarkTracker.Event> sideOutput() {
            return List.copyOf(late);
        }

        private static final class WindowStateAcc {
            private double sum;
            private int count;

            private void add(double value) {
                sum += value;
                count++;
            }
        }
    }
}

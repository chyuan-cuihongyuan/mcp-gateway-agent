package cn.chyuan.ai.domain.windowkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 触发器与累加（工单 0561 BO5，flink trigger 思想）。
 * watermark 到达窗尾触发/计数阈值提前触发/累加（Accumulating 全量）与
 * 撤回（Retract 增量+撤回记录）两种模式输出语义/两模式一致性换算断言。
 */
public final class WindowTrigger {

    public enum Mode {
        ACCUMULATING, RETRACT
    }

    /** 触发输出：窗键 + 当前聚合值 + 撤回的上一次值（Retract 模式才有） */
    public record TriggerOutput(WindowAssigner.Window window, double value, Double retractedPrevious) {
    }

    private final long countThreshold;
    private final Mode mode;

    public WindowTrigger(long countThreshold, Mode mode) {
        if (countThreshold < 1) {
            throw new IllegalArgumentException("计数阈值须≥1");
        }
        if (mode == null) {
            throw new IllegalArgumentException("模式不得为 null");
        }
        this.countThreshold = countThreshold;
        this.mode = mode;
    }

    public Mode mode() {
        return mode;
    }

    /**
     * 窗口内聚合推进：返回本次触发输出或 null（未触发）。
     * 计数到达阈值或 watermark 越过窗尾即触发。
     */
    public TriggerOutput onAggregate(WindowAssigner.Window window, int countInWindow, double runningSum,
            boolean watermarkFired) {
        if (countInWindow >= countThreshold || watermarkFired) {
            return emit(window, runningSum);
        }
        return null;
    }

    private TriggerOutput emit(WindowAssigner.Window window, double runningSum) {
        if (mode == Mode.ACCUMULATING) {
            return new TriggerOutput(window, runningSum, null);
        }
        Double previous = lastEmitted.get(window);
        lastEmitted.put(window, runningSum);
        return new TriggerOutput(window, runningSum - (previous == null ? 0.0d : previous), previous);
    }

    private final java.util.Map<WindowAssigner.Window, Double> lastEmitted = new java.util.HashMap<>();

    /** 两模式一致性换算：Retract 增量序列累加 = Accumulating 全量序列 */
    public static double retractAccumulates(List<TriggerOutput> retractOutputs) {
        double total = 0.0d;
        for (TriggerOutput output : retractOutputs) {
            total += output.value();
        }
        return total;
    }

    /** 累加模式最终值（最后一次输出即全量） */
    public static double accumulatingFinal(List<TriggerOutput> accumulateOutputs) {
        if (accumulateOutputs.isEmpty()) {
            return 0.0d;
        }
        return accumulateOutputs.get(accumulateOutputs.size() - 1).value();
    }

    /** 触发记录（供窗口统计） */
    public List<TriggerOutput> replay(WindowAssigner.Window window, List<Double> incrementalSums,
            boolean watermarkFired) {
        List<TriggerOutput> outputs = new ArrayList<>();
        int count = 0;
        double total = 0.0d;
        for (Double value : incrementalSums) {
            total += value;
            count++;
            TriggerOutput output = onAggregate(window, count, total, watermarkFired && count == incrementalSums.size());
            if (output != null) {
                outputs.add(output);
            }
        }
        return outputs;
    }
}

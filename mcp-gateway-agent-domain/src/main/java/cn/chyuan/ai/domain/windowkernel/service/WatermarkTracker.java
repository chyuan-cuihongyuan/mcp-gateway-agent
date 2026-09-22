package cn.chyuan.ai.domain.windowkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * 事件时间与 watermark（工单 0557 BO1，flink 事件时间语义思想）。
 * 事件时间与处理时间分离/watermark=最大事件时间-允许乱序延迟/单调推进/
 * 回退拒绝/时钟端口注入处理时间。window-kernel.enabled 默认关。
 */
public final class WatermarkTracker {

    /** 事件（事件时间 epoch ms + 键 + 值） */
    public record Event(long eventTime, String key, double value) {
    }

    private final long outOfOrdernessMillis;
    private final java.util.function.LongSupplier clock;
    private long maxEventTime = Long.MIN_VALUE;
    private long watermark = Long.MIN_VALUE;

    public WatermarkTracker(long outOfOrdernessMillis, java.util.function.LongSupplier clock) {
        if (outOfOrdernessMillis < 0) {
            throw new IllegalArgumentException("乱序延迟须非负");
        }
        if (clock == null) {
            throw new IllegalArgumentException("时钟端口不得为 null");
        }
        this.outOfOrdernessMillis = outOfOrdernessMillis;
        this.clock = clock;
    }

    public long processingTime() {
        return clock.getAsLong();
    }

    /**
     * 上报事件时间并推进 watermark：watermark=max(事件时间)-乱序延迟，
     * 只前进不回退；乱序事件合法（由 isLate 判迟到）。
     */
    public long observe(long eventTime) {
        if (eventTime > maxEventTime || maxEventTime == Long.MIN_VALUE) {
            maxEventTime = eventTime;
            watermark = maxEventTime - outOfOrdernessMillis;
        }
        return watermark;
    }

    /** 当前 watermark（未上报过为 Long.MIN_VALUE） */
    public long watermark() {
        return watermark;
    }

    public long maxEventTime() {
        return maxEventTime;
    }

    /** watermark 是否已越过窗口尾（窗口 [start,end) 可关闭） */
    public boolean firesWindowEnd(long windowEnd) {
        return watermark >= windowEnd;
    }

    /** 乱序检测：事件时间早于当前 watermark 即迟到 */
    public boolean isLate(long eventTime) {
        return watermark != Long.MIN_VALUE && eventTime < watermark;
    }

    /** 事件批量上报（按输入序；用于测试确定性重放） */
    public List<Long> observeAll(List<Long> eventTimes) {
        List<Long> marks = new ArrayList<>(eventTimes.size());
        for (Long time : eventTimes) {
            marks.add(observe(time));
        }
        return List.copyOf(marks);
    }
}

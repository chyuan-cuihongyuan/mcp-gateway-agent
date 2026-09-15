package cn.chyuan.ai.domain.streamkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 流指标与首 token 延迟（工单 0378 AT8，沿 0069 慢调用留痕形态）。
 * TTFT（首事件-请求起点）/吞吐（token/秒，滑动窗）/分片间隔 P50/P95/慢流标记。纯函数统计。
 */
public class StreamMetricsCalculator {

    private final long requestStartMs;
    private final long slowThresholdMs;
    private final long windowMs;

    private Long firstTokenMs;
    private final List<Long> chunkTimes = new ArrayList<>();
    private final List<Integer> chunkTokens = new ArrayList<>();

    public StreamMetricsCalculator(long requestStartMs, long slowThresholdMs, long windowMs) {
        this.requestStartMs = requestStartMs;
        this.slowThresholdMs = slowThresholdMs;
        this.windowMs = Math.max(1, windowMs);
    }

    /** 记录一个分片（时间戳 + 该分片 token 数） */
    public void onChunk(long atMs, int tokens) {
        if (firstTokenMs == null) {
            firstTokenMs = atMs;
        }
        chunkTimes.add(atMs);
        chunkTokens.add(Math.max(0, tokens));
    }

    /** 首 token 延迟（无分片返回 -1） */
    public long ttfbMs() {
        return firstTokenMs == null ? -1 : firstTokenMs - requestStartMs;
    }

    /** 是否慢流（TTFT 超阈值） */
    public boolean isSlow() {
        return ttfbMs() >= 0 && ttfbMs() > slowThresholdMs;
    }

    /** 滑动窗吞吐：窗内（最后 windowMs）token 总量 / 窗秒数（无分片 0） */
    public double throughputTokensPerSecond() {
        if (chunkTimes.isEmpty()) {
            return 0.0;
        }
        long windowStart = chunkTimes.get(chunkTimes.size() - 1) - windowMs;
        long tokens = 0;
        for (int i = 0; i < chunkTimes.size(); i++) {
            if (chunkTimes.get(i) >= windowStart) {
                tokens += chunkTokens.get(i);
            }
        }
        return round(tokens / (windowMs / 1000.0));
    }

    /** 分片间隔 P50（无间隔返回 -1） */
    public long intervalP50Ms() {
        return percentile(0.5);
    }

    /** 分片间隔 P95 */
    public long intervalP95Ms() {
        return percentile(0.95);
    }

    private long percentile(double q) {
        if (chunkTimes.size() < 2) {
            return -1;
        }
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < chunkTimes.size(); i++) {
            gaps.add(chunkTimes.get(i) - chunkTimes.get(i - 1));
        }
        gaps.sort(Long::compareTo);
        int idx = (int) Math.ceil(q * gaps.size()) - 1;
        idx = Math.max(0, Math.min(gaps.size() - 1, idx));
        return gaps.get(idx);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}

package cn.chyuan.ai.domain.governance.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 热点参数限流纯内核（工单 0226 AD7，借鉴 Sentinel hotspot）—
 * 按参数维度（模型/虚拟 Key）滑动计数窗口限流：每维度独立阈值与窗口；
 * 滑窗 = 窗口数 × 窗口长的环形计数（默认 2 桶），取窗口均速判定。
 *
 * @author chyuan
 */
public class HotspotParamLimiter {

    /** 单参数滑动窗口计数（环形桶） */
    static final class WindowCounter {
        private final long windowNanos;
        private final int buckets;
        private final AtomicLong[] counts;
        private final long[] bucketStarts;
        private final java.util.function.LongSupplier clockNanos;

        WindowCounter(long windowNanos, int buckets, java.util.function.LongSupplier clockNanos) {
            this.windowNanos = windowNanos;
            this.buckets = Math.max(2, buckets);
            this.counts = new AtomicLong[this.buckets];
            this.bucketStarts = new long[this.buckets];
            for (int i = 0; i < this.buckets; i++) {
                this.counts[i] = new AtomicLong();
                this.bucketStarts[i] = Long.MIN_VALUE;
            }
            this.clockNanos = clockNanos;
        }

        /** 当前桶计数 +1，返回本窗口（近 buckets 个桶）总计数 */
        long increment() {
            long now = clockNanos.getAsLong();
            int index = (int) ((now / (windowNanos / buckets)) % buckets);
            long bucketLength = windowNanos / buckets;
            long expectedStart = now - (now % bucketLength);
            if (bucketStarts[index] != expectedStart) {
                counts[index].set(0);
                bucketStarts[index] = expectedStart;
            }
            counts[index].incrementAndGet();
            long total = 0;
            for (int i = 0; i < buckets; i++) {
                if (bucketStarts[i] >= expectedStart - windowNanos + bucketLength) {
                    total += counts[i].get();
                }
            }
            return total;
        }
    }

    private final long windowNanos;
    private final int windowBuckets;
    private final java.util.function.LongSupplier clockNanos;
    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();

    public HotspotParamLimiter(long windowMs, java.util.function.LongSupplier clockNanos) {
        this.windowNanos = windowMs * 1_000_000L;
        this.windowBuckets = 2;
        this.clockNanos = clockNanos == null ? System::nanoTime : clockNanos;
    }

    /** 默认 10 秒窗口 */
    public HotspotParamLimiter() {
        this(10_000L, null);
    }

    /**
     * 判定：维度计数值超过阈值 → 拒绝。
     *
     * @param dimension 维度键（如 model:gpt-x / vk:123）
     * @param limit     窗口内允许的最大次数
     * @return true=放行（计数后未超阈）；false=超阈拒绝（计数已含本次）
     */
    public boolean tryAcquire(String dimension, long limit) {
        if (dimension == null || dimension.isBlank()) {
            return true;
        }
        WindowCounter counter = counters.computeIfAbsent(dimension,
                k -> new WindowCounter(windowNanos, windowBuckets, clockNanos));
        return counter.increment() <= limit;
    }

    /** 维度数（观测） */
    public int dimensionCount() {
        return counters.size();
    }
}

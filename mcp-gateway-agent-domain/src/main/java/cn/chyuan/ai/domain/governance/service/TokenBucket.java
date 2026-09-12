package cn.chyuan.ai.domain.governance.service;

/**
 * 令牌桶限流纯函数（工单 0225 AD6，Sentinel/通用令牌桶）—
 * 容量 capacity、补充速率 tokensPerSecond；clockNanos 注入保证可测确定性；
 * 允许突发（桶满可一次取 capacity 个）。
 *
 * @author chyuan
 */
public class TokenBucket {

    private final long capacity;
    private final double tokensPerSecond;
    private final java.util.function.LongSupplier clockNanos;

    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(long capacity, double tokensPerSecond, java.util.function.LongSupplier clockNanos) {
        if (capacity < 1) {
            capacity = 1;
        }
        if (tokensPerSecond <= 0) {
            tokensPerSecond = 1;
        }
        this.capacity = capacity;
        this.tokensPerSecond = tokensPerSecond;
        this.clockNanos = clockNanos == null ? System::nanoTime : clockNanos;
        this.tokens = capacity;
        this.lastRefillNanos = this.clockNanos.getAsLong();
    }

    /** 按经过时间补币（惰性） */
    private void refill() {
        long now = clockNanos.getAsLong();
        double added = (now - lastRefillNanos) / 1_000_000_000.0d * tokensPerSecond;
        if (added > 0) {
            tokens = Math.min(capacity, tokens + added);
            lastRefillNanos = now;
        }
    }

    /**
     * 尝试取 n 个令牌。
     *
     * @return 成功返回剩余令牌数；失败返回 -1
     */
    public synchronized long tryConsume(long n) {
        if (n < 0) {
            n = 0;
        }
        refill();
        if (tokens >= n) {
            tokens -= n;
            return (long) tokens;
        }
        return -1;
    }

    /** 当前可用令牌（触发惰性补币） */
    public synchronized long available() {
        refill();
        return (long) tokens;
    }

    /** 距下次可取 1 个令牌的等待毫秒（桶空时 > 0） */
    public synchronized long nanosToRefillOne() {
        refill();
        if (tokens >= 1) {
            return 0;
        }
        return (long) Math.ceil((1 - tokens) / tokensPerSecond * 1_000_000_000.0d);
    }

    public long capacity() {
        return capacity;
    }
}

package cn.chyuan.ai.domain.raftkernel.service;

/**
 * 选举超时与心跳（工单 0515 BJ4）。
 * 随机化超时（随机端口注入区间取值）/心跳重置计时/
 * 同任期同票防瓜分（重选仍唯一 leader，由 RaftState 单票保证）。
 */
public class ElectionTimer {

    /** 随机端口 */
    @FunctionalInterface
    public interface RandomPort {
        double nextDouble();
    }

    private final long minTimeoutMillis;
    private final long maxTimeoutMillis;
    private final RandomPort random;
    private long deadline;

    public ElectionTimer(long minTimeoutMillis, long maxTimeoutMillis, RandomPort random) {
        if (minTimeoutMillis <= 0 || maxTimeoutMillis < minTimeoutMillis) {
            throw new IllegalArgumentException("超时区间非法: 0 < min ≤ max");
        }
        this.minTimeoutMillis = minTimeoutMillis;
        this.maxTimeoutMillis = maxTimeoutMillis;
        this.random = random;
        this.deadline = randomizedTimeout(0L);
    }

    /** 随机化超时：[min, max) 内按随机端口取值 */
    public long randomizedTimeout(long nowMillis) {
        long span = maxTimeoutMillis - minTimeoutMillis;
        deadline = nowMillis + minTimeoutMillis + (long) (random.nextDouble() * span);
        return deadline - nowMillis;
    }

    /** 心跳到达：重置计时 */
    public long onHeartbeat(long nowMillis) {
        return randomizedTimeout(nowMillis);
    }

    /** 是否已超时（到时后自动重排下一轮随机超时） */
    public boolean expired(long nowMillis) {
        if (nowMillis >= deadline) {
            randomizedTimeout(nowMillis);
            return true;
        }
        return false;
    }

    public synchronized long remaining(long nowMillis) {
        return Math.max(0, deadline - nowMillis);
    }
}

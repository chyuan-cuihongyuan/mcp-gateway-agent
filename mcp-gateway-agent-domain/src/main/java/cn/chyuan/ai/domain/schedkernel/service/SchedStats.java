package cn.chyuan.ai.domain.schedkernel.service;

/**
 * 调度统计（工单 0738 CI7，golang GMP 思想）。
 * 完成吞吐/窃取次数/抢占与上交/排队深度峰值/每 G 等待与运行时长登记。
 */
public final class SchedStats {

    private long created;
    private long finished;
    private long steps;
    private long idleSpins;
    private long preemptions;
    private long netPollerWakes;
    private long rebalances;
    private long maxQueueDepth;

    void goroutinesCreated() {
        created++;
    }

    void goroutineFinished() {
        finished++;
    }

    void stepExecuted() {
        steps++;
    }

    void idleSpin() {
        idleSpins++;
    }

    void preemptions() {
        preemptions++;
    }

    void netPollerWakes() {
        netPollerWakes++;
    }

    void rebalances() {
        rebalances++;
    }

    public long preemptionCount() {
        return preemptions;
    }

    public long netPollerWakeCount() {
        return netPollerWakes;
    }

    public long rebalanceCount() {
        return rebalances;
    }

    public long created() {
        return created;
    }

    public long finished() {
        return finished;
    }

    public long steps() {
        return steps;
    }

    public long idleSpins() {
        return idleSpins;
    }

    public long maxQueueDepth() {
        return maxQueueDepth;
    }

    public void observeQueueDepth(int depth) {
        maxQueueDepth = Math.max(maxQueueDepth, depth);
    }
}

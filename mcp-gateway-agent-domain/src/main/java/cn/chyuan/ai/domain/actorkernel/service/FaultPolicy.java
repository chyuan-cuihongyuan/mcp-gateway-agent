package cn.chyuan.ai.domain.actorkernel.service;

import java.util.HashMap;
import java.util.Map;

/**
 * 故障处理（工单 1011 EI7，dapr 容错思想）。
 * turn 抛错计数/连续超阈失活/错误不终止队列后续（成功即清零）。
 */
public final class FaultPolicy {

    private final Map<String, Long> consecutiveFailures = new HashMap<>();
    private final Map<String, Long> totalFailures = new HashMap<>();

    /** 记录一次成功 turn：连续失败清零 */
    public synchronized void recordSuccess(String actorKey) {
        consecutiveFailures.put(actorKey, 0L);
    }

    /** 记录一次失败 turn：返回连续失败数 */
    public synchronized long recordFailure(String actorKey) {
        long consecutive = consecutiveFailures.merge(actorKey, 1L, Long::sum);
        totalFailures.merge(actorKey, 1L, Long::sum);
        return consecutive;
    }

    /** 连续失败数达到阈值即失活 */
    public synchronized boolean shouldDeactivate(String actorKey, long threshold) {
        return consecutiveFailures.getOrDefault(actorKey, 0L) >= threshold;
    }

    public synchronized long consecutive(String actorKey) {
        return consecutiveFailures.getOrDefault(actorKey, 0L);
    }

    public synchronized long total(String actorKey) {
        return totalFailures.getOrDefault(actorKey, 0L);
    }

    /** 失活后清除计数（再激活重新计） */
    public synchronized void clear(String actorKey) {
        consecutiveFailures.remove(actorKey);
    }
}

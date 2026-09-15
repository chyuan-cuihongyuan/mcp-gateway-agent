package cn.chyuan.ai.domain.streamkernel.service;

/**
 * 流式心跳与保活语义（工单 0375 AT5）。
 * 空闲到期发心跳（固定/指数退避节奏），连续 N 次到期无客户端活动判定断连；时钟由调用方注入。
 */
public class HeartbeatPolicy {

    /** 决策 */
    public enum Decision {
        NONE, SEND_PING, DISCONNECT
    }

    private final long baseIntervalMs;
    private final double backoffFactor;
    private final long maxIntervalMs;
    private final int disconnectAfterMisses;

    private long lastActivityMs;
    private long lastPingMs = Long.MIN_VALUE;
    private long currentIntervalMs;
    private int missedPings;

    public HeartbeatPolicy(long baseIntervalMs, double backoffFactor, long maxIntervalMs, int disconnectAfterMisses) {
        if (baseIntervalMs <= 0) {
            throw new IllegalArgumentException("基础间隔必须为正");
        }
        if (backoffFactor < 1.0) {
            throw new IllegalArgumentException("退避系数不可小于 1");
        }
        if (disconnectAfterMisses < 1) {
            throw new IllegalArgumentException("断连阈值至少 1 次");
        }
        this.baseIntervalMs = baseIntervalMs;
        this.backoffFactor = backoffFactor;
        this.maxIntervalMs = Math.max(maxIntervalMs, baseIntervalMs);
        this.disconnectAfterMisses = disconnectAfterMisses;
        this.currentIntervalMs = baseIntervalMs;
        this.lastActivityMs = Long.MIN_VALUE;
    }

    /** 注册客户端活动（重置退避与计数） */
    public void onActivity(long nowMs) {
        this.lastActivityMs = nowMs;
        this.missedPings = 0;
        this.currentIntervalMs = baseIntervalMs;
    }

    /**
     * 时钟推进判定：到期发心跳（按退避节奏），连续到期无活动达阈值判断连。
     */
    public Decision tick(long nowMs) {
        if (lastActivityMs == Long.MIN_VALUE) {
            lastActivityMs = nowMs;
            return Decision.NONE;
        }
        long sincePing = lastPingMs == Long.MIN_VALUE ? Long.MAX_VALUE : nowMs - lastPingMs;
        boolean due = sincePing >= currentIntervalMs;
        if (!due) {
            return Decision.NONE;
        }
        lastPingMs = nowMs;
        missedPings++;
        if (missedPings >= disconnectAfterMisses) {
            return Decision.DISCONNECT;
        }
        // 退避：下次间隔放大
        currentIntervalMs = Math.min(maxIntervalMs, (long) (currentIntervalMs * backoffFactor));
        return Decision.SEND_PING;
    }

    /** 当前退避间隔（观测） */
    public long currentIntervalMs() {
        return currentIntervalMs;
    }

    /** 已连续错过次数（观测） */
    public int missedPings() {
        return missedPings;
    }
}

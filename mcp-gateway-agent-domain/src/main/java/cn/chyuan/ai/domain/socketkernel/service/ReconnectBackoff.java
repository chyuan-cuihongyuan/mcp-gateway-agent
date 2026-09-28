package cn.chyuan.ai.domain.socketkernel.service;

/**
 * 重连退避（工单 0966 ED7，socket.io 思想）。
 * 断线触发/倍增序列/上限封顶/重连成功重置。
 */
public final class ReconnectBackoff {

    private final long baseMillis;
    private final double multiplier;
    private final long maxMillis;
    private int attempts = 0;

    public ReconnectBackoff(long baseMillis, double multiplier, long maxMillis) {
        if (baseMillis <= 0 || multiplier < 1 || maxMillis < baseMillis) {
            throw new IllegalArgumentException("退避参数非法: " + baseMillis + "/" + multiplier + "/" + maxMillis);
        }
        this.baseMillis = baseMillis;
        this.multiplier = multiplier;
        this.maxMillis = maxMillis;
    }

    /** 断线后下一次重连延迟：base * multiplier^attempts，封顶 max；无断线（attempts=0）拒绝 */
    public long nextDelayMillis() {
        if (attempts <= 0) {
            throw new IllegalStateException("未断线无退避");
        }
        long delay = baseMillis;
        for (int i = 1; i < attempts; i++) {
            delay = Math.min((long) (delay * multiplier), maxMillis);
        }
        return Math.min(delay, maxMillis);
    }

    /** 记一次断线 */
    public void onDisconnect() {
        attempts++;
    }

    /** 重连成功重置 */
    public void reset() {
        attempts = 0;
    }

    public int attempts() {
        return attempts;
    }
}

package cn.chyuan.ai.domain.governance.service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 半开熔断状态机（工单 0177 Y1，借鉴 Sentinel 三态）— 纯函数化状态流转：
 * <pre>
 * CLOSED --连续失败≥threshold--> OPEN --冷却期满--> HALF_OPEN --试探成功×successTrials--> CLOSED
 *                                   ^----------------试探失败----------------|
 * </pre>
 * 试探单发（HALF_OPEN 同时只放行一个请求，其余快速失败），时间由时钟注入可测。
 */
public class HalfOpenBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final int successTrialsToClose;
    private final long openCooldownMs;
    private final java.util.function.LongSupplier clock;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private int trialSuccesses;
    private long openedAtMs;
    private boolean trialInFlight;

    public HalfOpenBreaker(int failureThreshold, int successTrialsToClose, long openCooldownMs) {
        this(failureThreshold, successTrialsToClose, openCooldownMs, System::currentTimeMillis);
    }

    public HalfOpenBreaker(int failureThreshold, int successTrialsToClose, long openCooldownMs,
                           java.util.function.LongSupplier clock) {
        if (failureThreshold <= 0 || successTrialsToClose <= 0 || openCooldownMs < 0) {
            throw new IllegalArgumentException("熔断参数非法");
        }
        this.failureThreshold = failureThreshold;
        this.successTrialsToClose = successTrialsToClose;
        this.openCooldownMs = openCooldownMs;
        this.clock = clock;
    }

    /**
     * 请求准入：CLOSED 放行；OPEN 且冷却期满转入 HALF_OPEN 并占住试探位放行（试探单发）；
     * OPEN 冷却未满或试探位被占 → 拒绝。
     */
    public synchronized boolean tryAcquire() {
        long now = clock.getAsLong();
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (now - openedAtMs >= openCooldownMs) {
                    state = State.HALF_OPEN;
                    trialInFlight = true;
                    trialSuccesses = 0;
                    return true;
                }
                return false;
            case HALF_OPEN:
            default:
                if (trialInFlight) {
                    return false;
                }
                trialInFlight = true;
                return true;
        }
    }

    /** 失败回灌：CLOSED 累计连败达阈值转 OPEN；HALF_OPEN 试探失败回 OPEN（重新计时） */
    public synchronized void onFailure() {
        long now = clock.getAsLong();
        if (state == State.HALF_OPEN) {
            toOpen(now);
            return;
        }
        consecutiveFailures++;
        if (state == State.CLOSED && consecutiveFailures >= failureThreshold) {
            toOpen(now);
        }
    }

    /** 成功回灌：HALF_OPEN 试探成功累计达 successTrials 转 CLOSED 清零；CLOSED 清零连败 */
    public synchronized void onSuccess() {
        if (state == State.HALF_OPEN) {
            trialInFlight = false;
            trialSuccesses++;
            if (trialSuccesses >= successTrialsToClose) {
                state = State.CLOSED;
                consecutiveFailures = 0;
                trialSuccesses = 0;
            }
            return;
        }
        consecutiveFailures = 0;
    }

    /** 试探被拒绝后释放占位不应发生（拒绝者未占位）；仅由成功/失败路径释放 */
    public synchronized State state() {
        return state;
    }

    private void toOpen(long now) {
        state = State.OPEN;
        openedAtMs = now;
        trialInFlight = false;
        trialSuccesses = 0;
        consecutiveFailures = 0;
    }
}

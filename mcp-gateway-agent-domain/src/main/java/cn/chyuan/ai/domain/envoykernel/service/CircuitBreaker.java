package cn.chyuan.ai.domain.envoykernel.service;

/**
 * 熔断池（工单 0939 EA4，envoy 思想）。
 * 最大连接与挂起请求预算/超限拒绝/释放回池（释放时挂起优先晋升）。
 */
public final class CircuitBreaker {

    public enum Slot { CONNECTED, PENDING, REJECTED }

    private final int maxConnections;
    private final int maxPending;
    private int active = 0;
    private int pending = 0;

    public CircuitBreaker(int maxConnections, int maxPending) {
        if (maxConnections <= 0 || maxPending <= 0) {
            throw new IllegalArgumentException("熔断预算非法: " + maxConnections + "/" + maxPending);
        }
        this.maxConnections = maxConnections;
        this.maxPending = maxPending;
    }

    /** 获取槽位：连接未满入池；连接满挂起未满挂起；均满拒绝 */
    public Slot acquire() {
        if (active < maxConnections) {
            active++;
            return Slot.CONNECTED;
        }
        if (pending < maxPending) {
            pending++;
            return Slot.PENDING;
        }
        return Slot.REJECTED;
    }

    /** 释放一个连接：挂起队列优先晋升补位 */
    public void release() {
        if (active == 0) {
            throw new IllegalStateException("无连接可释放");
        }
        active--;
        if (pending > 0) {
            pending--;
            active++;
        }
    }

    public int active() {
        return active;
    }

    public int pending() {
        return pending;
    }
}

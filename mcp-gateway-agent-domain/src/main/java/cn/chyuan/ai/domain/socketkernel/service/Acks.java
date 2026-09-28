package cn.chyuan.ai.domain.socketkernel.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ack 回执（工单 0964 ED5，socket.io 思想）。
 * 事件带 ack 注册/应答回填/超时拒绝回调。
 */
public final class Acks {

    static final class Pending {
        String payload;
        int left;
        boolean settled;

        Pending(int left) {
            this.left = left;
        }
    }

    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final int timeoutTicks;
    private int seq = 0;

    public Acks(int timeoutTicks) {
        if (timeoutTicks <= 0) {
            throw new IllegalArgumentException("ack 超时步数非法: " + timeoutTicks);
        }
        this.timeoutTicks = timeoutTicks;
    }

    /** 注册一个待回执：返回 ack id */
    public String register() {
        String id = "ack_" + (++seq);
        pending.put(id, new Pending(timeoutTicks));
        return id;
    }

    /** 应答回填：未知 ack 拒绝 */
    public void resolve(String ackId, String payload) {
        Pending p = pending.get(ackId);
        if (p == null) {
            throw new IllegalStateException("未知 ack: " + ackId);
        }
        p.payload = payload;
        p.settled = true;
    }

    /** 收取：已回填返回载荷；未回填抛等待中；超时移除后抛拒绝 */
    public String take(String ackId) {
        Pending p = pending.get(ackId);
        if (p == null) {
            throw new IllegalStateException("ack 未注册或已超时: " + ackId);
        }
        if (p.payload == null) {
            throw new IllegalStateException("ack 等待回填: " + ackId);
        }
        pending.remove(ackId);
        return p.payload;
    }

    /** 时钟步进：未回填倒计时，到期移除 */
    public void tick() {
        pending.values().removeIf(p -> !p.settled && --p.left <= 0);
    }

    public int waiting() {
        return pending.size();
    }

    public int timeoutTicks() {
        return timeoutTicks;
    }
}

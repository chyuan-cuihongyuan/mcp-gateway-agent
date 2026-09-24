package cn.chyuan.ai.domain.schedkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * channel 缓冲语义（工单 0736 CI5，golang GMP 思想）。
 * 缓冲满发送挂起（完成标志记录，唤醒后动作自查重试）/空接收挂起（值随完成标志直投）/
 * 缓冲优先于挂起发送者/close 后收尽即返/向已关闭发送拒绝。
 */
public final class Channel {

    /** 挂起完成记录：唤醒方置值置标志，挂起方重试时读取 */
    private static final class Pending {
        final G g;
        boolean done;
        String value;

        Pending(G g) {
            this.g = g;
        }
    }

    private final Scheduler scheduler;
    private final java.util.Deque<String> buffer = new java.util.ArrayDeque<>();
    private final int capacity;
    private boolean closed;
    private final Map<G, Pending> pendingSends = new LinkedHashMap<>();
    private final Map<G, Pending> pendingRecvs = new LinkedHashMap<>();

    public Channel(Scheduler scheduler, int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("channel 容量须为正");
        }
        this.scheduler = scheduler;
        this.capacity = capacity;
    }

    /** 发送：挂起重试完成 → 等待接收者直投完成 → 缓冲有余入缓冲 → 记录挂起 */
    public void send(G self, String value) {
        if (closed) {
            throw new IllegalStateException("向已关闭 channel 发送");
        }
        Pending mine = pendingSends.get(self);
        if (mine != null) {
            if (!mine.done) {
                scheduler.parkCurrent(null);
                return;
            }
            pendingSends.remove(self);
            return;
        }
        Pending receiver = firstPending(pendingRecvs);
        if (receiver != null) {
            complete(receiver, value);
            return;
        }
        if (buffer.size() < capacity) {
            buffer.addLast(value);
            return;
        }
        Pending pending = new Pending(self);
        pending.value = value;
        pendingSends.put(self, pending);
        scheduler.parkCurrent(null);
    }

    /** 接收：挂起重试完成 → 缓冲 → 完成最早挂起发送者并直取其值 → 关闭返 null → 记录挂起 */
    public String recv(G self) {
        Pending mine = pendingRecvs.get(self);
        if (mine != null) {
            if (!mine.done) {
                scheduler.parkCurrent(null);
                return null;
            }
            pendingRecvs.remove(self);
            return mine.value;
        }
        if (!buffer.isEmpty()) {
            return buffer.pollFirst();
        }
        Pending sender = firstPending(pendingSends);
        if (sender != null) {
            String value = sender.value;
            complete(sender, null);
            return value;
        }
        if (closed) {
            return null;
        }
        Pending pending = new Pending(self);
        pendingRecvs.put(self, pending);
        scheduler.parkCurrent(null);
        return null;
    }

    public void close() {
        if (closed) {
            throw new IllegalStateException("重复关闭");
        }
        closed = true;
    }

    public int buffered() {
        return buffer.size();
    }

    public boolean isClosed() {
        return closed;
    }

    private static Pending firstPending(Map<G, Pending> pending) {
        for (Pending p : pending.values()) {
            if (!p.done) {
                return p;
            }
        }
        return null;
    }

    private void complete(Pending pending, String value) {
        pending.value = value;
        pending.done = true;
        scheduler.ready(pending.g, 0);
    }
}

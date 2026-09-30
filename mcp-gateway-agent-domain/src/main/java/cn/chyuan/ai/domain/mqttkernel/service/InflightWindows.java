package cn.chyuan.ai.domain.mqttkernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * inflight 窗口（工单 1076 EQ3，emqx 思想）。
 * 窗口满暂停新发（offer 返回 false）/PUBACK·PUBCOMP 释放滑窗/
 * PUBREL 相位保持占位不清窗不重发/超时重传退避倍增（timeout·2^retries）。
 */
public final class InflightWindows {

    private static final class Slot {
        final int packetId;
        long sentAt;
        int retries;
        final long retryTimeoutMs;

        Slot(int packetId, long sentAt, long retryTimeoutMs) {
            this.packetId = packetId;
            this.sentAt = sentAt;
            this.retryTimeoutMs = retryTimeoutMs;
        }
    }

    private final int capacity;
    private final Map<Integer, Slot> slots = new HashMap<>();

    public InflightWindows(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("窗口容量必须为正: " + capacity);
        }
        this.capacity = capacity;
    }

    /** 试图占用窗口；满则拒绝（暂停新发），由调用方排队 */
    public boolean offer(int packetId, long nowMs, long retryTimeoutMs) {
        if (slots.size() >= capacity && !slots.containsKey(packetId)) {
            return false;
        }
        slots.put(packetId, new Slot(packetId, nowMs, retryTimeoutMs));
        return true;
    }

    /** 释放窗口（PUBACK/PUBCOMP）；未知报文拒绝 */
    public void release(int packetId) {
        if (slots.remove(packetId) == null) {
            throw new IllegalArgumentException("未知报文拒绝释放: " + packetId);
        }
    }

    /** PUBREL 相位：保持占位（不清窗不重发），等待 PUBCOMP */
    public void holdRelay(int packetId) {
        if (!slots.containsKey(packetId)) {
            throw new IllegalArgumentException("未知报文拒绝占位: " + packetId);
        }
    }

    /** 到期重传：返回需重发报文 id；退避倍增并刷新发送时刻 */
    public List<Integer> dueResend(long nowMs) {
        List<Slot> due = new ArrayList<>();
        for (Slot slot : slots.values()) {
            long deadline = slot.sentAt + slot.retryTimeoutMs * (1L << slot.retries);
            if (nowMs > deadline) {
                due.add(slot);
            }
        }
        List<Integer> ids = new ArrayList<>();
        for (Slot slot : due) {
            ids.add(slot.packetId);
            slot.retries++;
            slot.sentAt = nowMs;
        }
        return ids;
    }

    public int retries(int packetId) {
        Slot slot = slots.get(packetId);
        if (slot == null) {
            throw new IllegalArgumentException("未知报文拒绝查询: " + packetId);
        }
        return slot.retries;
    }

    public int inflight() {
        return slots.size();
    }
}

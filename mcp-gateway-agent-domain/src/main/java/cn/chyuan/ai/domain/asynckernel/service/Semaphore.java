package cn.chyuan.ai.domain.asynckernel.service;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 信号量（工单 1000 EH4，tokio Semaphore 思想）。
 * acquire 许可递减/无许可排队 FIFO/release 唤醒队首优先授予/超容量释放拒绝。
 */
public final class Semaphore {

    private final int capacity;
    private int available;
    private final Deque<String> waiters = new ArrayDeque<>();
    private final Deque<String> granted = new ArrayDeque<>();

    public Semaphore(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("信号量容量必须为正: " + capacity);
        }
        this.capacity = capacity;
        this.available = capacity;
    }

    public int capacity() {
        return capacity;
    }

    public int available() {
        return available;
    }

    public int waiters() {
        return waiters.size();
    }

    /** 有许可则递减即刻成功；被唤醒者优先授予；否则入等待队列排队（FIFO），返回 false */
    public synchronized boolean acquire(String who) {
        if (who == null || who.isEmpty()) {
            throw new IllegalArgumentException("acquire 主体为空");
        }
        if (granted.remove(who)) {
            return true;
        }
        if (available > 0) {
            available--;
            return true;
        }
        waiters.addLast(who);
        return false;
    }

    /** 释放：有等待者唤醒队首（许可定向授予，不回流）；无等待者许可回流，超容量拒绝 */
    public synchronized void release() {
        String head = waiters.pollFirst();
        if (head != null) {
            granted.addLast(head);
            return;
        }
        if (available >= capacity) {
            throw new IllegalStateException("释放超容量: " + capacity);
        }
        available++;
    }
}

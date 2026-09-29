package cn.chyuan.ai.domain.asynckernel.service;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * mpsc 有界通道（工单 1001 EH5，tokio mpsc 思想）。
 * send 入队/receive 出队 FIFO/满时 send 拒绝背压/关闭后 send 拒绝。
 */
public final class Channel<T> {

    private final int capacity;
    private final Deque<T> queue = new ArrayDeque<>();
    private boolean closed;

    public Channel(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("通道容量必须为正: " + capacity);
        }
        this.capacity = capacity;
    }

    public int capacity() {
        return capacity;
    }

    public int size() {
        return queue.size();
    }

    public boolean isClosed() {
        return closed;
    }

    /** 发送：已关闭拒绝/满时拒绝（背压，不阻塞不丢弃）/未满入队 */
    public synchronized void send(T item) {
        if (closed) {
            throw new IllegalStateException("通道已关闭，send 拒绝");
        }
        if (queue.size() >= capacity) {
            throw new IllegalStateException("背压：通道已满 " + capacity);
        }
        queue.addLast(item);
    }

    /** 接收：FIFO 出队，空返回 null */
    public synchronized T receive() {
        return queue.pollFirst();
    }

    /** 关闭：幂等，关闭后剩余数据仍可收完 */
    public synchronized void close() {
        closed = true;
    }
}

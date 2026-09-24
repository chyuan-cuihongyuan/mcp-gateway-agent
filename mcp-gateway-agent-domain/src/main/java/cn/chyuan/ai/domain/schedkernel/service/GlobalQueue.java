package cn.chyuan.ai.domain.schedkernel.service;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 全局队列（工单 0733 CI2，golang GMP 思想）。
 * FIFO 全局队列/容量上限拒绝/深度统计。
 */
public final class GlobalQueue {

    private final Deque<G> queue = new ArrayDeque<>();
    private final int capacity;
    private long maxDepth;

    public GlobalQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("全局容量须为正");
        }
        this.capacity = capacity;
    }

    public void push(G g) {
        if (queue.size() >= capacity) {
            throw new IllegalStateException("全局队列满: " + queue.size());
        }
        queue.addLast(g);
        maxDepth = Math.max(maxDepth, queue.size());
    }

    public G pop() {
        return queue.pollFirst();
    }

    public int size() {
        return queue.size();
    }

    public long maxDepth() {
        return maxDepth;
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }
}

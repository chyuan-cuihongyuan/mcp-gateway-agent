package cn.chyuan.ai.domain.schedkernel.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * P 本地队列（工单 0733 CI2，golang GMP 思想）。
 * P 本地环形队列（256 槽）/本地满一半上交全局/取数优先本地再全局/全局队列容量拒绝。
 */
public final class P {

    public static final int DEFAULT_LOCAL_CAPACITY = 256;

    private final int id;
    private final Deque<G> local = new ArrayDeque<>();
    private final int localCapacity;
    private final GlobalQueue global;
    private long stealCount;
    private long handoffCount;

    public P(int id, GlobalQueue global, int localCapacity) {
        if (localCapacity <= 0) {
            throw new IllegalArgumentException("本地容量须为正");
        }
        this.id = id;
        this.global = global;
        this.localCapacity = localCapacity;
    }

    public int id() {
        return id;
    }

    public int localSize() {
        return local.size();
    }

    public long stealCount() {
        return stealCount;
    }

    public long handoffCount() {
        return handoffCount;
    }

    /** 入队：达半容量上交一半到全局（runnext 保底留下） */
    public void submit(G g) {
        if (local.size() >= localCapacity / 2) {
            int handoff = local.size() / 2;
            for (int i = 0; i < handoff; i++) {
                global.push(local.pollFirst());
                handoffCount++;
            }
        }
        if (local.size() >= localCapacity) {
            throw new IllegalStateException("P 本地队列满: " + id);
        }
        local.addLast(g);
    }

    /** 取数：优先本地，再全局 */
    public G next() {
        G g = local.pollFirst();
        if (g != null) {
            return g;
        }
        return global.pop();
    }

    /** 窃取他 P 队尾一半 */
    int stealFromTail() {
        if (local.isEmpty()) {
            return 0;
        }
        int half = Math.max(1, local.size() / 2);
        for (int i = 0; i < half; i++) {
            global.push(local.pollLast());
        }
        stealCount += half;
        return half;
    }

    List<G> drainForTest() {
        return List.copyOf(local);
    }
}

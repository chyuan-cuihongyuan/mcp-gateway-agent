package cn.chyuan.ai.domain.schedkernel.service;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 负载均衡与 netpoller 模拟（工单 0737 CI6，golang GMP 思想）。
 * netpoller 就绪注入（IO 完成事件回全局队列）/均衡抽查间隔可配/注入统计。
 */
public final class NetPoller {

    /** 就绪事件：预注册的 IO 完成回调（到期即注入） */
    private final Deque<G> readyQueue = new ArrayDeque<>();
    private long injected;
    private int checkInterval;

    public NetPoller() {
        this(1);
    }

    public NetPoller(int checkInterval) {
        if (checkInterval <= 0) {
            throw new IllegalArgumentException("检查间隔须为正");
        }
        this.checkInterval = checkInterval;
    }

    /** 模拟 IO 完成：G 直接进就绪池（checkpoint 时注入全局） */
    public void ioComplete(G g, G.State ignoredCurrentState) {
        if (g.state() != G.State.WAITING) {
            throw new IllegalStateException("仅 WAITING G 可被 netpoller 唤醒");
        }
        readyQueue.add(g);
    }

    /** checkpoint 拉取一个就绪 G */
    G pollReady() {
        G g = readyQueue.pollFirst();
        if (g != null) {
            injected++;
        }
        return g;
    }

    public int pending() {
        return readyQueue.size();
    }

    public long injected() {
        return injected;
    }

    public int checkInterval() {
        return checkInterval;
    }
}

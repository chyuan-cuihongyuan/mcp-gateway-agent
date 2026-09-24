package cn.chyuan.ai.domain.schedkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * sync 语义（工单 0736 CI5，golang GMP 思想）。
 * WaitGroup Add·Done·Wait 挂起唤醒/channel 缓冲满发空收阻塞/
 * close 后收尽即返/全 waiting 死锁检测（调度器侧）。
 */
public final class WaitGroup {

    private final Scheduler scheduler;
    private long counter;
    private final List<G> waiters = new ArrayList<>();

    public WaitGroup(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    public void add(long delta) {
        if (counter + delta < 0) {
            throw new IllegalStateException("WaitGroup 计数下溢");
        }
        counter += delta;
        if (counter == 0) {
            releaseWaiters();
        }
    }

    public void done() {
        add(-1);
    }

    public void wait(G self) {
        if (counter == 0) {
            return;
        }
        waiters.add(self);
        scheduler.parkCurrent(null);
    }

    public long counter() {
        return counter;
    }

    public int waiters() {
        return waiters.size();
    }

    private void releaseWaiters() {
        for (G waiter : waiters) {
            scheduler.ready(waiter, 0);
        }
        waiters.clear();
    }
}

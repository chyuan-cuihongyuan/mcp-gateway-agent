package cn.chyuan.ai.domain.asynckernel.service;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * waker 唤醒（工单 0998 EH2，tokio 思想）。
 * wake 一次置就绪/重复 wake 幂等/未唤醒不调度/wake 后任务进入就绪车道待执行。
 */
public final class Wakers {

    /** waker 句柄：绑定任务名，一次性置就绪 */
    public static final class Waker {
        final String task;
        boolean woken;

        Waker(String task) {
            this.task = task;
        }

        public String task() {
            return task;
        }

        public boolean isWoken() {
            return woken;
        }
    }

    private final Deque<String> readyQueue = new ArrayDeque<>();

    /** 登记未唤醒 waker */
    public synchronized Waker register(String task) {
        if (task == null || task.isEmpty()) {
            throw new IllegalArgumentException("waker 任务名为空");
        }
        return new Waker(task);
    }

    /** 唤醒：首次置就绪并入就绪车道；重复唤醒幂等不入队 */
    public synchronized void wake(Waker waker) {
        if (waker == null) {
            throw new IllegalArgumentException("waker 为空");
        }
        if (waker.woken) {
            return;
        }
        waker.woken = true;
        readyQueue.addLast(waker.task);
    }

    /** 就绪车道取出：未唤醒不出现，wake 后待执行 */
    public synchronized String pollReady() {
        return readyQueue.pollFirst();
    }

    public synchronized int readyCount() {
        return readyQueue.size();
    }
}

package cn.chyuan.ai.domain.asynckernel.service;

import java.util.List;

import cn.chyuan.ai.domain.asynckernel.service.Tasks.JoinHandle;

/**
 * 异步运行时端口（工单 1004 EH8，tokio 思想）。
 * spawn·select·timeout 入口统一编排/与 machinekernel 状态机作任务状态轨迹形态只读联动（泛型状态串不 import）/
 * async-kernel.enabled 默认关（开启才改变行为）。
 */
public interface AsyncPort {

    // —— 任务状态机（EH1）——
    String spawn(String name);

    Tasks.State state(String taskId);

    JoinHandle handle(String taskId);

    void complete(String taskId, Object value);

    void cancelTask(String taskId);

    // —— waker 唤醒（EH2）——
    Wakers.Waker registerWaker(String task);

    void wake(Wakers.Waker waker);

    String pollReady();

    // —— select 公平轮转（EH3）——
    String select(List<Selects.Branch> branches);

    // —— 信号量（EH4）——
    boolean acquire(String who);

    void release();

    // —— mpsc 有界通道（EH5）——
    void channelOpen(String name, int capacity);

    void send(String channel, String item);

    String receive(String channel);

    void channelClose(String channel);

    // —— 虚拟时钟（EH6）——
    long now();

    String timeout(long delayMs, Runnable action);

    String interval(long periodMs, Runnable action);

    void advance(long deltaMs);

    boolean cancelTimer(String timerId);

    // —— 取消令牌（EH7）——
    CancellationToken newToken();

    // —— machinekernel 状态机形态只读联动：任务状态迁移轨迹（READY->COMPLETED）——
    String stateTrace(String taskId);

    static AsyncPort inMemory() {
        return new AsyncHub();
    }
}

package cn.chyuan.ai.domain.asynckernel.service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cn.chyuan.ai.domain.asynckernel.service.Tasks.JoinHandle;

/**
 * 异步运行时编排实现（工单 1004 EH8，tokio 思想）。
 * 组合任务状态机/waker/select/信号量/通道/虚拟时钟/取消令牌；
 * machinekernel 状态机形态只读联动：任务迁移轨迹按「READY->COMPLETED」状态串形状落定（形状数据不 import machinekernel）。
 */
public final class AsyncHub implements AsyncPort {

    private final Tasks tasks = new Tasks();
    private final Wakers wakers = new Wakers();
    private final Selects selects = new Selects();
    private final Semaphore semaphore = new Semaphore(1);
    private final Map<String, Channel<String>> channels = new LinkedHashMap<>();
    private final VirtualClock clock = new VirtualClock();
    private final Map<String, StringBuilder> traces = new HashMap<>();

    @Override
    public String spawn(String name) {
        String taskId = tasks.spawn(name);
        traces.put(taskId, new StringBuilder(Tasks.State.READY.name()));
        return taskId;
    }

    @Override
    public Tasks.State state(String taskId) {
        return tasks.state(taskId);
    }

    @Override
    public JoinHandle handle(String taskId) {
        return tasks.handle(taskId);
    }

    @Override
    public void complete(String taskId, Object value) {
        tasks.complete(taskId, value);
        trace(taskId, Tasks.State.COMPLETED.name());
    }

    @Override
    public void cancelTask(String taskId) {
        tasks.cancel(taskId);
        trace(taskId, Tasks.State.CANCELLED.name());
    }

    @Override
    public Wakers.Waker registerWaker(String task) {
        return wakers.register(task);
    }

    @Override
    public void wake(Wakers.Waker waker) {
        wakers.wake(waker);
    }

    @Override
    public String pollReady() {
        return wakers.pollReady();
    }

    @Override
    public String select(List<Selects.Branch> branches) {
        return selects.select(branches);
    }

    @Override
    public boolean acquire(String who) {
        return semaphore.acquire(who);
    }

    @Override
    public void release() {
        semaphore.release();
    }

    @Override
    public void channelOpen(String name, int capacity) {
        if (channels.containsKey(name)) {
            throw new IllegalArgumentException("通道已存在: " + name);
        }
        channels.put(name, new Channel<>(capacity));
    }

    private Channel<String> channel(String name) {
        Channel<String> channel = channels.get(name);
        if (channel == null) {
            throw new IllegalArgumentException("通道不存在: " + name);
        }
        return channel;
    }

    @Override
    public void send(String channel, String item) {
        channel(channel).send(item);
    }

    @Override
    public String receive(String channel) {
        return channel(channel).receive();
    }

    @Override
    public void channelClose(String channel) {
        channel(channel).close();
    }

    @Override
    public long now() {
        return clock.now();
    }

    @Override
    public String timeout(long delayMs, Runnable action) {
        return clock.timeout(delayMs, action);
    }

    @Override
    public String interval(long periodMs, Runnable action) {
        return clock.interval(periodMs, action);
    }

    @Override
    public void advance(long deltaMs) {
        clock.advance(deltaMs);
    }

    @Override
    public boolean cancelTimer(String timerId) {
        return clock.cancel(timerId);
    }

    @Override
    public CancellationToken newToken() {
        return CancellationToken.create();
    }

    @Override
    public String stateTrace(String taskId) {
        StringBuilder trace = traces.get(taskId);
        if (trace == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
        return trace.toString();
    }

    private void trace(String taskId, String state) {
        StringBuilder trace = traces.get(taskId);
        if (trace != null) {
            trace.append("->").append(state);
        }
    }
}

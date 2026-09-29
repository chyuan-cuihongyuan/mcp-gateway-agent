package cn.chyuan.ai.domain.asynckernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 异步运行时内核测试（工单 0997-1004 EH1-EH8，tokio 思想）。
 * 任务状态机/waker 唤醒/select 公平轮转/信号量/mpsc 有界通道/虚拟时钟/取消令牌/端口组合管线。
 */
class AsyncKernelTest {

    @Test
    void taskStateMachine() {
        Tasks tasks = new Tasks();
        String id = tasks.spawn("job");
        assertEquals(Tasks.State.READY, tasks.state(id));
        Tasks.JoinHandle handle = tasks.handle(id);
        assertNull(handle.await(), "未完成挂起返回 null");
        assertFalse(handle.isDone());
        tasks.complete(id, "v1");
        assertEquals(Tasks.State.COMPLETED, tasks.state(id));
        assertTrue(handle.isDone());
        assertEquals("v1", handle.await(), "await 只读可重复");
        assertEquals("v1", handle.await());
        assertEquals("v1", handle.take(), "完成值一次性取");
        assertThrows(IllegalStateException.class, handle::take, "重复取拒绝");
        assertThrows(IllegalStateException.class, () -> tasks.complete(id, "v2"), "重复完成拒绝");
        assertThrows(IllegalArgumentException.class, () -> tasks.state("nope"), "未知任务拒绝");

        String c = tasks.spawn("cancel-job");
        tasks.cancel(c);
        assertEquals(Tasks.State.CANCELLED, tasks.state(c));
        assertTrue(tasks.handle(c).isDone());
        assertNull(tasks.handle(c).await(), "取消无完成值");
        assertThrows(IllegalStateException.class, () -> tasks.handle(c).take(), "取消任务取值拒绝");
        assertThrows(IllegalStateException.class, () -> tasks.complete(c, "x"), "取消后完成拒绝");
    }

    @Test
    void wakerWakeOnce() {
        Wakers wakers = new Wakers();
        Wakers.Waker waker = wakers.register("job");
        assertFalse(waker.isWoken());
        assertNull(wakers.pollReady(), "未唤醒不调度");
        assertEquals(0, wakers.readyCount());
        wakers.wake(waker);
        assertTrue(waker.isWoken());
        assertEquals("job", wakers.pollReady(), "wake 后任务进入就绪车道");
        assertNull(wakers.pollReady());
        wakers.wake(waker);
        assertNull(wakers.pollReady(), "重复 wake 幂等不入队");
        assertThrows(IllegalArgumentException.class, () -> wakers.register(""), "空任务名拒绝");
        assertThrows(IllegalArgumentException.class, () -> wakers.wake(null), "空 waker 拒绝");
    }

    @Test
    void selectFairnessRotation() {
        Selects selects = new Selects();
        Selects.Branch ready = new Selects.Branch("a", () -> true);
        assertNull(selects.select(List.of(new Selects.Branch("x", () -> false))), "全未就绪等待返回 null");
        assertEquals("a", selects.select(List.of(ready)), "首就绪胜出");
        assertEquals("a", selects.select(List.of(
                new Selects.Branch("a", () -> true),
                new Selects.Branch("b", () -> true))), "同 tick 首就绪胜出");
        assertEquals("b", selects.select(List.of(
                new Selects.Branch("a", () -> true),
                new Selects.Branch("b", () -> true))), "轮转起点递进选下一就绪");
        assertEquals("a", selects.select(List.of(
                new Selects.Branch("a", () -> true),
                new Selects.Branch("b", () -> true))), "继续轮转回绕");
        assertThrows(IllegalArgumentException.class, () -> selects.select(List.of()), "空分支拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> new Selects.Branch("", () -> true), "空分支名拒绝");
    }

    @Test
    void semaphoreQueueAndRelease() {
        Semaphore semaphore = new Semaphore(2);
        assertTrue(semaphore.acquire("a"), "有许可递减成功");
        assertTrue(semaphore.acquire("b"));
        assertEquals(0, semaphore.available());
        assertFalse(semaphore.acquire("c"), "无许可排队");
        assertFalse(semaphore.acquire("d"));
        assertEquals(2, semaphore.waiters());
        semaphore.release();
        assertEquals(1, semaphore.waiters(), "release 唤醒队首出队");
        assertTrue(semaphore.acquire("c"), "被唤醒者优先授予");
        assertFalse(semaphore.acquire("e"), "未唤醒者仍排队");
        assertEquals(2, semaphore.waiters(), "d 与 e 依次排队");
        semaphore.release();
        assertTrue(semaphore.acquire("d"), "队首个别唤醒授予");
        semaphore.release();
        assertTrue(semaphore.acquire("e"), "唤醒后授予跟随");
        semaphore.release();
        assertEquals(1, semaphore.available(), "无等待者许可回流");
        semaphore.release();
        assertEquals(2, semaphore.available());
        assertThrows(IllegalStateException.class, semaphore::release, "超容量释放拒绝");
        assertThrows(IllegalArgumentException.class, () -> new Semaphore(0), "容量必须为正");
        assertThrows(IllegalArgumentException.class, () -> semaphore.acquire(""), "空主体拒绝");
    }

    @Test
    void channelBackpressureAndClose() {
        Channel<String> channel = new Channel<>(2);
        channel.send("m1");
        channel.send("m2");
        assertEquals(2, channel.size());
        assertThrows(IllegalStateException.class, () -> channel.send("m3"), "满时 send 背压拒绝");
        assertEquals("m1", channel.receive(), "FIFO 出队");
        assertEquals("m2", channel.receive());
        assertNull(channel.receive(), "空返回 null");
        channel.close();
        channel.close();
        assertTrue(channel.isClosed());
        assertThrows(IllegalStateException.class, () -> channel.send("m4"), "关闭后 send 拒绝");
        assertThrows(IllegalArgumentException.class, () -> new Channel<>(0), "容量必须为正");
    }

    @Test
    void virtualClockTimers() {
        VirtualClock clock = new VirtualClock();
        assertEquals(0, clock.now());
        AtomicInteger fired = new AtomicInteger();
        String timeoutId = clock.timeout(100, fired::incrementAndGet);
        clock.advance(50);
        assertEquals(0, fired.get(), "未到期不触发");
        clock.advance(49);
        assertEquals(0, fired.get());
        clock.advance(1);
        assertEquals(1, fired.get(), "timeout 到期触发");
        assertEquals(100, clock.now());
        assertFalse(clock.cancel(timeoutId), "已触发不可取消");

        AtomicInteger ticks = new AtomicInteger();
        clock.interval(60, ticks::incrementAndGet);
        clock.advance(60);
        assertEquals(1, ticks.get());
        clock.advance(120);
        assertEquals(3, ticks.get(), "interval 周期触发");
        clock.advance(30);
        assertEquals(3, ticks.get());
        assertThrows(IllegalArgumentException.class, () -> clock.advance(-1), "负步长拒绝");
        assertThrows(IllegalArgumentException.class, () -> clock.timeout(-1, () -> {
        }), "负延迟拒绝");
        assertThrows(IllegalArgumentException.class, () -> clock.interval(0, () -> {
        }), "零周期拒绝");
    }

    @Test
    void cancellationTree() {
        CancellationToken root = CancellationToken.create();
        List<String> log = new java.util.ArrayList<>();
        CancellationToken child1 = root.child();
        CancellationToken child2 = root.child();
        root.onDispose(() -> log.add("root-cb"));
        child1.onDispose(() -> log.add("child1-cb"));
        child2.cancel();
        assertTrue(child2.isCancelled());
        assertFalse(root.isCancelled(), "子取消不传父");
        assertFalse(child1.isCancelled(), "兄弟不受影响");

        root.cancel();
        assertTrue(root.isCancelled());
        assertEquals(List.of("root-cb", "child1-cb"), log, "父取消级联传播子并执行回调");
        root.cancel();
        assertEquals(List.of("root-cb", "child1-cb"), log, "重复取消幂等");

        child1.onDispose(() -> log.add("late-cb"));
        assertEquals(List.of("root-cb", "child1-cb", "late-cb"), log, "已取消注册回调即触发");
        assertThrows(IllegalArgumentException.class, () -> root.onDispose(null), "空回调拒绝");
    }

    @Test
    void asyncPortPipeline() {
        AsyncPort port = AsyncPort.inMemory();
        // 任务 + 状态轨迹（machinekernel 形态只读联动）
        String taskId = port.spawn("pipeline");
        Wakers.Waker waker = port.registerWaker(taskId);
        port.complete(taskId, "done");
        assertEquals(Tasks.State.COMPLETED, port.state(taskId));
        assertEquals("READY->COMPLETED", port.stateTrace(taskId), "状态迁移轨迹形态");
        assertEquals("done", port.handle(taskId).take());

        String cancelled = port.spawn("cancelled");
        port.cancelTask(cancelled);
        assertEquals("READY->CANCELLED", port.stateTrace(cancelled));
        assertThrows(IllegalArgumentException.class, () -> port.stateTrace("nope"));

        // waker + 信号量 + 通道
        port.wake(waker);
        assertEquals(taskId, port.pollReady());
        assertTrue(port.acquire("gate"));
        assertFalse(port.acquire("waiter"));
        port.release();
        assertTrue(port.acquire("waiter"), "release 后可获许可");
        port.release();
        port.channelOpen("ch", 2);
        port.send("ch", "a");
        assertEquals("a", port.receive("ch"));
        port.channelClose("ch");
        assertThrows(IllegalStateException.class, () -> port.send("ch", "b"));
        assertThrows(IllegalArgumentException.class, () -> port.send("nope", "b"), "未知通道拒绝");

        // 时钟 + 取消令牌
        AtomicInteger fired = new AtomicInteger();
        port.timeout(10, fired::incrementAndGet);
        port.advance(10);
        assertEquals(1, fired.get());
        CancellationToken token = port.newToken();
        CancellationToken child = token.child();
        token.cancel();
        assertTrue(child.isCancelled());
    }
}

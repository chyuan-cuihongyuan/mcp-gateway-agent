package cn.chyuan.ai.domain.actorkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 虚拟Actor内核测试（工单 1005-1012 EI1-EI8，dapr 思想）。
 * 类型与 ID/turn 串行化/定时器/提醒/空闲失活/actor 间调用/故障处理/端口组合管线。
 */
class ActorKernelTest {

    @Test
    void actorRegistryAndActivation() {
        Actors actors = new Actors();
        actors.registerType("counter");
        actors.registerType("counter");
        assertThrows(IllegalStateException.class, () -> actors.activate("ghost", "1"), "未注册类型拒绝");
        Actors.Actor first = actors.activate("counter", "1");
        Actors.Actor second = actors.activate("counter", "1");
        assertSame(first, second, "按需激活幂等返回同实例");
        assertEquals(1, actors.activeCount());
        first.putState("n", 7);
        assertEquals(7, actors.get("counter", "1").getState("n"));

        actors.registerType("gauge");
        Actors.Actor otherType = actors.activate("gauge", "1");
        otherType.putState("n", 100);
        assertEquals(7, actors.get("counter", "1").getState("n"), "跨类型同 ID 互不可见");
        assertEquals(100, actors.get("gauge", "1").getState("n"));

        actors.deactivate("counter", "1");
        assertFalse(actors.isActive("counter", "1"));
        assertThrows(IllegalStateException.class, () -> actors.deactivate("counter", "1"), "未激活失活拒绝");
    }

    @Test
    void turnSerialization() {
        TurnQueue queue = new TurnQueue();
        assertNull(queue.beginTurn(), "空队列不空转返回 null");
        queue.send("m1");
        queue.send("m2");
        assertEquals(2, queue.pending());
        assertEquals("m1", queue.beginTurn(), "FIFO 开 turn");
        assertTrue(queue.inTurn());
        assertThrows(IllegalStateException.class, queue::beginTurn, "turn 内独占重入拒绝");
        queue.send("m3");
        assertEquals(2, queue.pending(), "turn 中消息照常排队");
        queue.endTurn();
        assertFalse(queue.inTurn());
        assertEquals("m2", queue.beginTurn());
        queue.endTurn();
        assertThrows(IllegalStateException.class, queue::endTurn, "无 turn 结束拒绝");
        assertThrows(IllegalArgumentException.class, () -> queue.send(null), "空消息拒绝");
    }

    @Test
    void actorTimersOneShotAndRepeat() {
        ActorTimers timers = new ActorTimers();
        AtomicInteger oneShot = new AtomicInteger();
        AtomicInteger repeated = new AtomicInteger();
        timers.register("counter/1", "once", 2, false, oneShot::incrementAndGet);
        timers.register("counter/1", "loop", 2, true, repeated::incrementAndGet);
        timers.step();
        assertEquals(0, oneShot.get());
        timers.step();
        assertEquals(1, oneShot.get(), "一次性到期触发");
        assertEquals(1, repeated.get());
        timers.step();
        assertEquals(1, oneShot.get(), "一次性触发后失效");
        timers.step();
        assertEquals(2, repeated.get(), "周期持续触发");
        timers.register("counter/1", "loop", 5, true, repeated::incrementAndGet);
        timers.step();
        timers.step();
        timers.step();
        timers.step();
        assertEquals(2, repeated.get(), "重复注册覆盖：旧触发计划作废");
        timers.cancelAll("counter/1");
        int before = repeated.get();
        timers.step();
        timers.step();
        timers.step();
        timers.step();
        timers.step();
        timers.step();
        assertEquals(before, repeated.get(), "失活取消定时器");
        assertThrows(IllegalArgumentException.class,
                () -> timers.register("counter/1", "bad", 0, true, () -> {
                }), "零周期拒绝");
    }

    @Test
    void remindersPersistAndReplay() {
        Reminders reminders = new Reminders();
        reminders.register("counter/1", "due", 3, false);
        reminders.register("counter/1", "tick", 3, true);
        for (int i = 0; i < 3; i++) {
            reminders.step();
        }
        assertEquals(List.of("due", "tick"), reminders.replay("counter/1"), "激活后到期重放");
        assertEquals(List.of(), reminders.replay("counter/1"), "一次性触发后失效不再重放");
        for (int i = 0; i < 3; i++) {
            reminders.step();
        }
        assertEquals(List.of("tick"), reminders.replay("counter/1"), "周期提醒持续触发");
        reminders.register("counter/1", "due", 2, false);
        assertEquals(2, reminders.pending(), "覆盖后重新计入");
        assertTrue(reminders.unregister("counter/1", "due"));
        assertFalse(reminders.unregister("counter/1", "due"), "重复注销返回 false");
        assertThrows(IllegalArgumentException.class,
                () -> reminders.register("counter/1", "bad", 0, false), "零周期拒绝");
    }

    @Test
    void idleReactivation() {
        IdleReaper idle = new IdleReaper();
        idle.touch("counter/1");
        idle.tick();
        idle.touch("counter/1");
        assertEquals(0, idle.idle("counter/1"), "活动重置计数");
        idle.tick();
        idle.tick();
        assertEquals(2, idle.idle("counter/1"));
        assertTrue(idle.reap(2).isEmpty(), "未超阈值不收割");
        idle.tick();
        assertEquals(List.of("counter/1"), idle.reap(2), "超阈值失活收割");
        assertEquals(0, idle.idle("counter/1"), "收割后计数移除");
        idle.touch("counter/1");
        assertEquals(0, idle.idle("counter/1"), "失活后再激活重建");
    }

    @Test
    void actorCallCycleDetection() {
        ActorCalls calls = new ActorCalls();
        calls.enter(null, "A");
        calls.enter("A", "A");
        assertEquals("A->A", calls.trace(), "自调用允许");
        calls.exit();
        calls.enter("A", "B");
        assertEquals("A->B", calls.trace());
        assertThrows(IllegalStateException.class, () -> calls.enter("B", "A"), "调用环检测拒绝");
        calls.exit();
        calls.exit();
        assertEquals(0, calls.depth());
        assertThrows(IllegalStateException.class, calls::exit, "空栈退出拒绝");
        assertThrows(IllegalArgumentException.class, () -> calls.enter(null, ""), "空目标拒绝");
    }

    @Test
    void faultPolicyConsecutive() {
        FaultPolicy faults = new FaultPolicy();
        assertEquals(1, faults.recordFailure("counter/1"));
        assertEquals(2, faults.recordFailure("counter/1"));
        assertTrue(faults.shouldDeactivate("counter/1", 2), "连续超阈判失活");
        faults.recordSuccess("counter/1");
        assertEquals(0, faults.consecutive("counter/1"), "成功清零");
        assertFalse(faults.shouldDeactivate("counter/1", 2));
        faults.recordFailure("counter/1");
        assertFalse(faults.shouldDeactivate("counter/1", 2), "清零后重新累计");
        assertEquals(3, faults.total("counter/1"), "累计失败不清零");
        faults.clear("counter/1");
        assertEquals(0, faults.consecutive("counter/1"), "失活清除计数");
        assertThrows(IllegalArgumentException.class, () -> new IdleReaper().touch(null), "空 actor 键拒绝");
    }

    @Test
    void actorPortPipeline() {
        ActorPort port = ActorPort.inMemory();
        port.registerType("counter");
        assertEquals("counter/1", port.activate("counter", "1"), "激活幂等");
        port.activate("counter", "1");
        port.send("counter", "1", "inc");
        port.send("counter", "1", "inc");
        assertEquals("actor[counter/1]inbox=2", port.queueShape("counter", "1"), "schedkernel 队列形状联动");

        assertTrue(port.beginTurn("counter", "1"));
        assertEquals("inc", port.currentTurn("counter", "1"));
        port.endTurn("counter", "1", true);
        assertTrue(port.beginTurn("counter", "1"));
        port.endTurn("counter", "1", false);
        assertFalse(port.beginTurn("counter", "1"), "队列空不空转");

        AtomicInteger fired = new AtomicInteger();
        port.registerTimer("counter", "1", "beat", 2, true, fired::incrementAndGet);
        port.registerReminder("counter", "1", "due", 3, false);
        port.tick();
        port.tick();
        assertEquals(1, fired.get(), "定时器周期触发");

        port.tick();
        assertEquals(List.of("due"), port.replayReminders("counter", "1"), "提醒激活重放");

        port.send("counter", "1", "hold");
        assertThrows(IllegalStateException.class, () -> port.deactivate("counter", "1"), "inbox 非空失活拒绝");
        assertTrue(port.beginTurn("counter", "1"));
        port.endTurn("counter", "1", true);
        port.deactivate("counter", "1");
        assertFalse(port.isActive("counter", "1"));

        port.enterCall(null, "counter/1");
        port.enterCall("counter/1", "counter/1");
        assertEquals(2, port.callDepth(), "自调用允许入栈");
        port.exitCall();
        port.enterCall("counter/1", "peer/1");
        assertThrows(IllegalStateException.class, () -> port.enterCall("peer/1", "counter/1"), "环检测拒绝");
        port.exitCall();
        port.exitCall();
    }
}

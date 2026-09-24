package cn.chyuan.ai.domain.schedkernel.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 调度内核测试（工单 0732-0738 CI1-CI7，golang GMP 思想）。
 * G 状态机合法性/本地队列满载上交/窃取/时间片抢占/WaitGroup·channel 阻塞唤醒/
 * 死锁检测/netpoller 注入/均衡抽查/统计。
 */
class SchedKernelTest {

    @Test
    void gStateTransitionsLegality() {
        G g = new G("t", List.of((sched, self) -> { }));
        assertEquals(G.State.RUNNABLE, g.state());
        assertTrue(g.id() > 0);
        assertTrue(new G("t2", List.of()).id() > g.id(), "id 单调");

        g.transit(G.State.RUNNING, 0);
        g.transit(G.State.WAITING, 1);
        g.transit(G.State.RUNNABLE, 2);
        assertEquals(1, g.waitTicks(), "等待时长登记");
        g.transit(G.State.RUNNING, 2);
        g.transit(G.State.RUNNABLE, 3);
        assertEquals(1, g.runTicks());
        g.transit(G.State.RUNNING, 3);
        g.transit(G.State.DEAD, 3);
        assertThrows(IllegalStateException.class, () -> g.transit(G.State.RUNNABLE, 4), "终态不可复活");
        assertThrows(IllegalStateException.class, () -> g.transit(G.State.WAITING, 4));

        G idle = new G("idle", List.of());
        assertThrows(IllegalStateException.class, () -> idle.transit(G.State.WAITING, 0), "RUNNABLE→WAITING 非法");
    }

    @Test
    void pQueueHandoffAndGlobalCapacity() {
        GlobalQueue global = new GlobalQueue(4);
        P p = new P(0, global, 4);
        G a = new G("a", List.of());
        G b = new G("b", List.of());
        G c = new G("c", List.of());
        p.submit(a);
        assertEquals(1, p.localSize());
        p.submit(b);
        assertEquals(2, p.localSize(), "未达半容量不上交");
        p.submit(c);
        assertEquals(2, p.localSize(), "达半容量上交一半");
        assertEquals(1, global.size());
        assertEquals(1, p.handoffCount());
        assertEquals(b, p.next(), "本地优先（a 已上交全局）");
        assertEquals(c, p.next(), "本地取完接全局上交者");
        assertNotNull(p.next(), "本地空后取全局");
        assertNull(p.next());

        GlobalQueue tight = new GlobalQueue(1);
        tight.push(a);
        assertThrows(IllegalStateException.class, () -> tight.push(b), "全局容量拒绝");
    }

    @Test
    void stealFromTailHalf() {
        GlobalQueue global = new GlobalQueue(16);
        P victim = new P(1, global, 8);
        for (int i = 0; i < 5; i++) {
            victim.submit(new G("g" + i, List.of()));
        }
        assertEquals(3, victim.localSize(), "提交期已触发一次半量上交");
        assertEquals(2, global.size());
        assertEquals(1, victim.stealFromTail(), "窃取队尾一半");
        assertEquals(2, victim.localSize());
        assertEquals(3, global.size());
        assertEquals(1, victim.stealCount());
        assertEquals(0, new P(2, global, 8).stealFromTail(), "空 P 无可窃");
    }

    @Test
    void schedulerRunToCompletionWithTrace() {
        Scheduler s = Scheduler.of(2);
        G one = new G("one", List.of(
                (sched, self) -> sched.recordStep(self, "s1"),
                (sched, self) -> sched.recordStep(self, "s2")));
        G two = new G("two", List.of(
                (sched, self) -> sched.recordStep(self, "t1"),
                (sched, self) -> sched.recordStep(self, "t2"),
                (sched, self) -> sched.recordStep(self, "t3")));
        s.go(0, one);
        s.go(1, two);
        s.runUntilIdle();
        assertEquals(2, s.stats().finished());
        assertEquals(5, s.stats().steps());
        assertEquals(5, s.trace().size());
        assertTrue(s.trace().contains("one:s1"));
        assertTrue(s.trace().contains("two:t3"));
        assertEquals(G.State.DEAD, one.state());
        assertEquals(G.State.DEAD, two.state());
    }

    @Test
    void preemptionYieldsAtBudget() {
        GlobalQueue global = new GlobalQueue(64);
        Scheduler s = new Scheduler(1, global, new NetPoller(), 2, 64);
        List<String> order = new ArrayList<>();
        G longTask = new G("long", List.of(
                (sched, self) -> order.add("a"),
                (sched, self) -> order.add("b"),
                (sched, self) -> order.add("c"),
                (sched, self) -> order.add("d"),
                (sched, self) -> order.add("e")));
        s.go(0, longTask);
        s.runUntilIdle();
        assertEquals(5, order.size());
        assertTrue(s.stats().preemptionCount() >= 1, "时间片超限让出");
        assertEquals(1, s.stats().finished());
        assertEquals(0, global.size(), "让出后最终收尾");
    }

    @Test
    void waitGroupParksAndWakes() {
        Scheduler s = Scheduler.of(1);
        WaitGroup wg = new WaitGroup(s);
        G worker = new G("worker", List.of(
                (sched, self) -> wg.add(1),
                (sched, self) -> wg.add(1),
                (sched, self) -> wg.done(),
                (sched, self) -> wg.done()));
        G waiter = new G("waiter", List.of(
                (sched, self) -> wg.wait(self),
                (sched, self) -> sched.recordStep(self, "after-wait")));
        s.go(0, worker);
        s.go(0, waiter);
        s.runUntilIdle();
        assertEquals(0, wg.counter());
        assertTrue(s.trace().contains("waiter:after-wait"), "唤醒后继续执行");
        assertEquals(G.State.DEAD, waiter.state());
        assertThrows(IllegalStateException.class, () -> wg.add(-1), "计数下溢拒绝");
    }

    @Test
    void channelBufferedBlockingAndClose() {
        Scheduler s = Scheduler.of(1);
        Channel ch = new Channel(s, 1);
        List<String> received = new ArrayList<>();
        G producer = new G("producer", List.of(
                (sched, self) -> ch.send(self, "a"),
                (sched, self) -> ch.send(self, "b"),
                (sched, self) -> ch.send(self, "c")));
        G consumer = new G("consumer", List.of(
                (sched, self) -> {
                    String v = ch.recv(self);
                    if (v != null) {
                        received.add(v);
                    }
                },
                (sched, self) -> {
                    String v = ch.recv(self);
                    if (v != null) {
                        received.add(v);
                    }
                },
                (sched, self) -> {
                    String v = ch.recv(self);
                    if (v != null) {
                        received.add(v);
                    }
                }));
        s.go(0, producer);
        s.go(0, consumer);
        s.runUntilIdle();
        assertEquals(List.of("a", "b", "c"), received, "阻塞重试语义下三值全达");
        assertEquals(0, ch.buffered());
        assertEquals(G.State.DEAD, producer.state());
        assertEquals(G.State.DEAD, consumer.state());

        Channel closed = new Channel(s, 1);
        closed.close();
        assertThrows(IllegalStateException.class, () -> closed.send(consumer, "x"), "关后发送拒绝");
        assertThrows(IllegalStateException.class, closed::close, "重复关闭拒绝");
        assertNull(closed.recv(consumer), "关后收尽即返 null");
    }

    @Test
    void netPollerInjectsReadyG() {
        Scheduler s = Scheduler.of(1);
        boolean[] parked = {false};
        G ioTask = new G("io", List.of(
                (sched, self) -> {
                    if (!parked[0]) {
                        parked[0] = true;
                        sched.parkCurrent(null);
                    }
                }));
        s.go(0, ioTask);
        s.step();
        assertEquals(G.State.WAITING, ioTask.state(), "挂起等 IO");
        assertThrows(IllegalStateException.class, () -> s.netPoller().ioComplete(new G("x", List.of()), null),
                "非 WAITING 拒绝注入");
        s.netPoller().ioComplete(ioTask, G.State.WAITING);
        s.runUntilIdle();
        assertEquals(1, s.netPoller().injected(), "就绪注入");
        assertEquals(G.State.DEAD, ioTask.state());
    }

    @Test
    void deadlockDetectedWhenAllWaiting() {
        Scheduler s = Scheduler.of(1);
        WaitGroup stuck = new WaitGroup(s);
        stuck.add(1);
        G orphan = new G("orphan", List.of(
                (sched, self) -> stuck.wait(self)));
        s.go(0, orphan);
        assertThrows(IllegalStateException.class, s::runUntilIdle, "全阻塞死锁检测");
        assertEquals(G.State.WAITING, orphan.state());
    }

    @Test
    void rebalanceAndStats() {
        GlobalQueue global = new GlobalQueue(64);
        Scheduler s = new Scheduler(2, global, new NetPoller(), 4, 2);
        for (int i = 0; i < 6; i++) {
            G g = new G("burst" + i, List.of(
                    (sched, self) -> sched.recordStep(self, "run")));
            s.go(0, g);
        }
        for (int i = 0; i < 12; i++) {
            s.step();
        }
        assertTrue(s.stats().rebalanceCount() >= 1, "均衡抽查触发");
        assertEquals(6, s.stats().created());
        assertTrue(s.stats().steps() > 0);
        assertTrue(s.stats().maxQueueDepth() >= 0);
        assertTrue(s.global().maxDepth() >= 0);
    }
}

package cn.chyuan.ai.domain.msgkernel.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BB5-BB8 单测（工单 0447-0450）：幂等去重/背压水位/时间轮/流指标。
 */
class MsgFlowTest {

    @Test
    void 幂等去重拦截与TTL过期() {
        AtomicLong clock = new AtomicLong(1_000);
        IdempotentDedup dedup = new IdempotentDedup(1_000, clock::get);
        // 首次通过
        assertFalse(dedup.check("p1", 1).duplicate());
        // 重放重复拦截
        assertTrue(dedup.check("p1", 1).duplicate());
        assertEquals(1, dedup.suppressedTotal());
        // 不同序号通过
        assertFalse(dedup.check("p1", 2).duplicate());
        // TTL 过期后同序号放行（重入）
        clock.set(2_100);
        assertFalse(dedup.check("p1", 1).duplicate());
        // 指纹确定性
        assertEquals(IdempotentDedup.fingerprint("p1", 1), IdempotentDedup.fingerprint("p1", 1));
        // 非法窗口
        assertThrows(IllegalArgumentException.class, () -> new IdempotentDedup(0, clock::get));
    }

    @Test
    void 背压滞回与三策略() {
        // BLOCK：高水位 3/低水位 1
        BackpressureValve block = new BackpressureValve(3, 1, BackpressureValve.OverflowPolicy.BLOCK, null);
        block.enqueue("a");
        block.enqueue("b");
        block.enqueue("c");
        assertTrue(block.paused());
        assertFalse(block.enqueue("d").accepted());
        assertEquals(1, block.blockedTotal());
        // 出队至低水位（3→2→1）恢复
        block.dequeue();
        block.dequeue();
        assertFalse(block.paused());
        assertTrue(block.enqueue("d").accepted());

        // DROP_NEWEST：暂停期丢最新
        BackpressureValve drop = new BackpressureValve(2, 0, BackpressureValve.OverflowPolicy.DROP_NEWEST, null);
        drop.enqueue("a");
        drop.enqueue("b");
        assertFalse(drop.enqueue("c").accepted());
        assertEquals(1, drop.droppedTotal());
        assertEquals(2, drop.buffered());

        // TO_DLQ：暂停期转死信回调
        List<String> sink = new ArrayList<>();
        BackpressureValve dlq = new BackpressureValve(1, 0, BackpressureValve.OverflowPolicy.TO_DLQ, sink::add);
        dlq.enqueue("a");
        var result = dlq.enqueue("b");
        assertTrue(result.accepted());
        assertEquals(1, dlq.deadLetteredTotal());
        assertEquals(List.of("b"), sink);
        // 非法水位
        assertThrows(IllegalArgumentException.class,
                () -> new BackpressureValve(1, 1, BackpressureValve.OverflowPolicy.BLOCK, null));
    }

    @Test
    void 时间轮入轮出轮取消与降级() {
        AtomicLong clock = new AtomicLong(0);
        TimingWheel wheel = new TimingWheel(4, 4, clock::get);
        // 秒轮内：延迟 2s
        wheel.schedule(new TimingWheel.Timer("t1", "p1", clock.get() + 2_000));
        assertEquals(1, wheel.pending());
        // tick 1 次未到期，tick 2 次到期
        assertNull(wheel.tick().stream().filter(t -> t.id().equals("t1")).findFirst().orElse(null));
        List<TimingWheel.Timer> due = wheel.tick();
        assertTrue(due.stream().anyMatch(t -> t.id().equals("t1")));
        assertEquals(0, wheel.pending());
        // 取消：先调度后取消
        wheel.schedule(new TimingWheel.Timer("t2", "p2", clock.get() + 1_000));
        assertTrue(wheel.cancel("t2"));
        assertFalse(wheel.tick().stream().anyMatch(t -> t.id().equals("t2")));
        // 长延迟进溢出（>16s），随时间滚动降级后到期
        wheel.schedule(new TimingWheel.Timer("t3", "p3", clock.get() + 60_000));
        clock.set(50_000);
        wheel.schedule(new TimingWheel.Timer("t4", "p4", clock.get() + 3_000));
        boolean t3Due = false;
        for (int i = 0; i < 62 && !t3Due; i++) {
            clock.addAndGet(1_000);
            t3Due = wheel.tick().stream().anyMatch(t -> t.id().equals("t3"));
        }
        assertTrue(t3Due, "长延迟消息应经降级滚动后到期投递");
    }

    @Test
    void 流指标计数水位分级与快照() {
        MsgMetrics metrics = new MsgMetrics(10, 100);
        metrics.recordAppend();
        metrics.recordAppend();
        metrics.recordDeliver();
        metrics.recordAck();
        metrics.recordRetry();
        metrics.recordDeadLetter();
        // 分级
        assertEquals(MsgMetrics.LagLevel.OK, metrics.lagLevel(5));
        assertEquals(MsgMetrics.LagLevel.WARN, metrics.lagLevel(10));
        assertEquals(MsgMetrics.LagLevel.CRIT, metrics.lagLevel(100));
        // 快照确定性
        var snapshot = metrics.snapshot(5_000, 12);
        assertEquals(2L, snapshot.get("appended"));
        assertEquals(1L, snapshot.get("delivered"));
        assertEquals("WARN", snapshot.get("lagLevel"));
        assertEquals(5_000L, snapshot.get("capturedAtMs"));
        var again = metrics.snapshot(5_000, 12);
        assertEquals(snapshot, again);
        // 非法阈值
        assertThrows(IllegalArgumentException.class, () -> new MsgMetrics(100, 10));
    }
}

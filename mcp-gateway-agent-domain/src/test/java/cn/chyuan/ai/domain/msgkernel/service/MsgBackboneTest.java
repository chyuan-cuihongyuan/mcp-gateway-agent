package cn.chyuan.ai.domain.msgkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BB1-BB4 单测（工单 0443-0446）：WAL/消费组偏移/重放缺口/死信退避。
 */
class MsgBackboneTest {

    @Test
    void wal追加段滚动与区间扫描() {
        WriteAheadLog wal = new WriteAheadLog(3, 1_000);
        for (int i = 0; i < 7; i++) {
            assertEquals(i, wal.append("t", "m" + i, 1_000 + i));
        }
        // 段滚动：7 条/3 容量 → 3 段（2 密封 + 1 活跃）
        assertEquals(3, wal.segmentCount());
        assertEquals(7, wal.tail());
        // 跨段扫描 [1,5)
        List<WriteAheadLog.Entry> entries = wal.scan(1, 5);
        assertEquals(4, entries.size());
        assertEquals(1, entries.get(0).offset());
        assertEquals(4, entries.get(3).offset());
        // 越界拒绝
        assertThrows(IllegalArgumentException.class, () -> wal.scan(6, 9));
        assertThrows(IllegalArgumentException.class, () -> wal.scan(4, 2));
    }

    @Test
    void 消费组双游标与回退拒绝() {
        WriteAheadLog wal = new WriteAheadLog(64, 1_000);
        for (int i = 0; i < 10; i++) {
            wal.append("t", "m" + i, 1_000);
        }
        ConsumerOffsetRegistry registry = new ConsumerOffsetRegistry(ConsumerOffsetRegistry.Initial.EARLIEST);
        ConsumerOffsetRegistry.Batch batch = registry.fetch(wal, "g1", "t", 4);
        assertEquals(4, batch.entries().size());
        assertEquals(0, batch.fromOffset());
        assertTrue(registry.commit("g1", "t", 3));
        assertEquals(4, registry.committed("g1", "t"));
        // 回退拒绝
        assertFalse(registry.commit("g1", "t", 2));
        // 超过 delivered 拒绝
        assertFalse(registry.commit("g1", "t", 9));
        // 继续 fetch 从 delivered=4 起
        assertEquals(4, registry.fetch(wal, "g1", "t", 4).fromOffset());
        // lag = 尾部10 - 已提交4 = 6
        assertEquals(6, registry.lag(wal, "g1", "t"));
        // LATEST 新组从尾部开始
        ConsumerOffsetRegistry latest = new ConsumerOffsetRegistry(ConsumerOffsetRegistry.Initial.LATEST);
        assertTrue(latest.fetch(wal, "g2", "t", 4).entries().isEmpty());
    }

    @Test
    void 重放两种计划与缺口检测() {
        AtomicLong clock = new AtomicLong(1_000);
        WriteAheadLog wal = new WriteAheadLog(2, clock.get());
        wal.append("t", "m0", clock.get());
        wal.append("t", "m1", clock.incrementAndGet());
        wal.append("t", "m2", clock.incrementAndGet());
        wal.append("t", "m3", clock.incrementAndGet());
        ReplayCursor replay = new ReplayCursor();
        // 按偏移重放 [1,4)
        ReplayCursor.Plan byOffset = replay.replayByOffsets(wal, 1, 4);
        assertEquals(3, byOffset.entries().size());
        assertTrue(byOffset.gaps().isEmpty());
        // 同计划重放幂等
        assertEquals(byOffset.entries(), replay.replayByOffsets(wal, 1, 4).entries());
        // 按时间戳范围重放
        assertEquals(2, replay.replayByTime(wal, 1_001, 1_002).entries().size());
        // 缺口检测：序列断号 [1,3)
        List<WriteAheadLog.Entry> withHole = List.of(
                new WriteAheadLog.Entry(0, "t", "a", 1_000),
                new WriteAheadLog.Entry(3, "t", "b", 1_000));
        assertEquals(1, replay.detectGaps(withHole, 0, 4).size());
        assertEquals(1, replay.detectGaps(withHole, 0, 4).get(0).from());
        assertEquals(3, replay.detectGaps(withHole, 0, 4).get(0).toExclusive());
    }

    @Test
    void 死信退避封顶与重投复位() {
        AtomicLong clock = new AtomicLong(1_000);
        DeadLetterQueue dlq = new DeadLetterQueue(3, 100, 400, clock::get);
        // 第 1 次失败：退避 100ms
        var d1 = dlq.fail("m1", "t", 0, "boom");
        assertTrue(d1.retryScheduled());
        assertEquals(1_100, d1.nextRetryAtMs());
        assertFalse(dlq.readyToDeliver("m1"));
        // 时间推进可投；第 2 次失败退避 200ms（未到 400 封顶）
        clock.set(1_100);
        assertEquals(1_300, dlq.fail("m1", "t", 0, "boom").nextRetryAtMs());
        // 第 3 次失败入 DLQ
        clock.set(1_300);
        var d3 = dlq.fail("m1", "t", 0, "boom");
        assertTrue(d3.dead());
        assertEquals(1, dlq.list("t").size());
        assertEquals(3, dlq.list("t").get(0).attempts());
        // 其它主题查询为空
        assertTrue(dlq.list("other").isEmpty());
        // 重投复位
        assertTrue(dlq.redeliver("m1"));
        assertTrue(dlq.list("t").isEmpty());
        assertTrue(dlq.readyToDeliver("m1"));
        // 非法参数
        assertThrows(IllegalArgumentException.class, () -> new DeadLetterQueue(0, 100, 400, clock::get));
    }
}

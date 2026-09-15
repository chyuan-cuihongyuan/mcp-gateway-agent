package cn.chyuan.ai.domain.streamkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AT5-AT8 单测（工单 0375/0376/0377/0378）：心跳保活/断流重放/背压水位/流指标。
 */
class StreamKernelFlowTest {

    @Test
    void 心跳退避节奏与断连判定() {
        HeartbeatPolicy policy = new HeartbeatPolicy(1000L, 2.0, 8000L, 3);
        assertEquals(HeartbeatPolicy.Decision.NONE, policy.tick(0L));
        // 1000ms 到期 → 首次心跳
        assertEquals(HeartbeatPolicy.Decision.SEND_PING, policy.tick(1000L));
        assertEquals(2000L, policy.currentIntervalMs());
        // 客户端活动重置退避与计数
        policy.onActivity(1500L);
        assertEquals(1000L, policy.currentIntervalMs());
        assertEquals(0, policy.missedPings());
        // 连续到期退避：2500/4500/8500（间隔 2000/4000），第 4 次 misses=3 判 DISCONNECT
        assertEquals(HeartbeatPolicy.Decision.SEND_PING, policy.tick(2500L));
        assertEquals(HeartbeatPolicy.Decision.SEND_PING, policy.tick(4500L));
        assertEquals(HeartbeatPolicy.Decision.DISCONNECT, policy.tick(8500L));
        // 非法配置
        assertThrows(IllegalArgumentException.class, () -> new HeartbeatPolicy(0, 1.0, 100, 1));
    }

    @Test
    void 断流游标重放不重不漏与缺口检测() {
        ResumeReplayBuffer<String> buffer = new ResumeReplayBuffer<>(3);
        for (long seq = 1; seq <= 5; seq++) {
            buffer.offer(seq, "e" + seq);
        }
        // 缓冲只留 3/4/5
        var mid = buffer.replay(2L);
        assertFalse(mid.gap());
        assertEquals(List.of("e3", "e4", "e5"), mid.events());
        // 游标 0 早于最旧 3 → 缺口
        assertTrue(buffer.replay(0L).gap());
        assertEquals(List.of("e3", "e4", "e5"), buffer.replay(0L).events());
        // 最新游标：空重放
        assertTrue(buffer.replay(5L).events().isEmpty());
        // 序号严格递增拒绝
        assertThrows(IllegalArgumentException.class, () -> buffer.offer(5L, "dup"));
        // 重放幂等
        assertTrue(buffer.replayIdempotent(2L));
    }

    @Test
    void 背压高低水位滞回与三种溢出策略() {
        // DROP_OLDEST：高水位 3 低水位 1
        BackpressureController<Integer> dropOldest = new BackpressureController<>(3, 1,
                BackpressureController.OverflowPolicy.DROP_OLDEST);
        assertEquals(BackpressureController.Signal.NONE, dropOldest.push(1));
        assertEquals(BackpressureController.Signal.NONE, dropOldest.push(2));
        // 达高水位翻转沿 → PAUSE
        assertEquals(BackpressureController.Signal.PAUSE, dropOldest.push(3));
        // 满：丢最旧
        assertEquals(BackpressureController.Signal.NONE, dropOldest.push(4));
        assertEquals(3, dropOldest.size());
        assertEquals(List.of(2, 3, 4), dropOldest.snapshot());
        // 消费至低水位 → RESUME 翻转沿
        assertEquals(BackpressureController.Signal.NONE, dropOldest.pop());
        assertEquals(BackpressureController.Signal.RESUME, dropOldest.pop());
        assertFalse(dropOldest.isPaused());
        // DROP_NEWEST：满后丢弃新元素
        BackpressureController<Integer> dropNewest = new BackpressureController<>(2, 0,
                BackpressureController.OverflowPolicy.DROP_NEWEST);
        dropNewest.push(1);
        dropNewest.push(2);
        dropNewest.push(3);
        assertEquals(List.of(1, 2), dropNewest.snapshot());
        // ERROR：溢出抛错
        BackpressureController<Integer> error = new BackpressureController<>(1, 0,
                BackpressureController.OverflowPolicy.ERROR);
        error.push(1);
        assertThrows(IllegalStateException.class, () -> error.push(2));
        // 非法水位配置
        assertThrows(IllegalArgumentException.class,
                () -> new BackpressureController<Integer>(1, 1, BackpressureController.OverflowPolicy.ERROR));
    }

    @Test
    void 流指标TTFT吞吐与分位慢流标记() {
        StreamMetricsCalculator metrics = new StreamMetricsCalculator(1000L, 500L, 2000L);
        assertEquals(-1, metrics.ttfbMs());
        metrics.onChunk(1300L, 5);
        metrics.onChunk(1400L, 5);
        metrics.onChunk(1500L, 5);
        metrics.onChunk(5000L, 10);
        assertEquals(300L, metrics.ttfbMs());
        assertFalse(metrics.isSlow());
        // 分片间隔 100/100/3500：P50=100，P95=3500
        assertEquals(100L, metrics.intervalP50Ms());
        assertEquals(3500L, metrics.intervalP95Ms());
        // 滑动窗 2000ms：窗内只有 5000 分片 → 10 token / 2s = 5.0
        assertEquals(5.0, metrics.throughputTokensPerSecond());
        // 慢流标记：TTFT 超阈值
        StreamMetricsCalculator slow = new StreamMetricsCalculator(0L, 100L, 1000L);
        slow.onChunk(200L, 1);
        assertTrue(slow.isSlow());
    }
}

package cn.chyuan.ai.domain.windowkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 窗口端口组合管线 BO8 单测（工单 0564）：
 * 事件流→键控滚动窗聚合→watermark 触发输出确定性重放 + msgkernel 消息时间戳只读联动。
 */
class WindowPortPipelineTest {

    private List<WatermarkTracker.Event> events() {
        return List.of(
                new WatermarkTracker.Event(1_000L, "k1", 10.0d),
                new WatermarkTracker.Event(2_000L, "k1", 20.0d),
                new WatermarkTracker.Event(3_000L, "k2", 40.0d),
                new WatermarkTracker.Event(11_000L, "k1", 30.0d),
                new WatermarkTracker.Event(21_000L, "k1", 50.0d));
    }

    @Test
    void BO8_组合管线_键控滚动窗聚合watermark触发() {
        WindowPort.InMemoryWindowEngine engine = new WindowPort.InMemoryWindowEngine();
        WindowAssigner assigner = new WindowAssigner(10_000L, 0);
        WatermarkTracker tracker = new WatermarkTracker(0L, () -> 100_000L);
        List<WindowPort.WindowResult> results = engine.process(events(), assigner, tracker);
        assertEquals(4, results.size(), "k1 三窗 + k2 一窗");
        assertEquals(new WindowPort.WindowResult("k1", 0L, 2, 30.0d, "WATERMARK"), results.get(0));
        assertEquals(new WindowPort.WindowResult("k1", 10_000L, 1, 30.0d, "WATERMARK"), results.get(1));
        assertEquals(new WindowPort.WindowResult("k1", 20_000L, 1, 50.0d, "BUFFERED"),
                results.get(2), "窗尾 30s 未被 watermark=21s 越过，仍缓冲");
        assertEquals(new WindowPort.WindowResult("k2", 0L, 1, 40.0d, "WATERMARK"), results.get(3));
        assertTrue(engine.sideOutput().isEmpty(), "无迟到");
        // 确定性重放：同输入同输出
        WindowPort.InMemoryWindowEngine again = new WindowPort.InMemoryWindowEngine();
        assertEquals(results, again.process(events(), assigner, new WatermarkTracker(0L, () -> 100_000L)),
                "同输入同输出确定性");
    }

    @Test
    void BO8_迟到事件旁路与msgkernel消息时间戳联动() {
        WindowPort.InMemoryWindowEngine engine = new WindowPort.InMemoryWindowEngine();
        WindowAssigner assigner = new WindowAssigner(10_000L, 0);
        // watermark 只随事件推进（乱序延迟 0）：20s 事件使 0-10s 窗迟到
        WatermarkTracker tracker = new WatermarkTracker(0L, () -> 0L);
        List<WatermarkTracker.Event> stream = List.of(
                new WatermarkTracker.Event(1_000L, "k1", 1.0d),
                new WatermarkTracker.Event(20_000L, "k1", 2.0d),
                new WatermarkTracker.Event(500L, "k1", 3.0d));
        List<WindowPort.WindowResult> results = engine.process(stream, assigner, tracker);
        assertEquals(2, results.size());
        assertEquals(1, engine.sideOutput().size(), "迟到事件旁路");
        assertEquals(500L, engine.sideOutput().get(0).eventTime());
        // msgkernel 只读联动：消息记录（带时间戳）映射为事件喂窗口
        List<long[]> messages = List.of(new long[]{1L, 1_000L}, new long[]{2L, 2_000L});
        List<WatermarkTracker.Event> mapped = messages.stream()
                .map(m -> new WatermarkTracker.Event(m[1], "msg-" + m[0], 1.0d)).toList();
        assertEquals(2, engine.process(mapped, assigner, new WatermarkTracker(0L, () -> 100_000L)).size(),
                "消息时间戳喂窗口（只读转换不改 msgkernel）");
    }

    @Test
    void BO8_滑动窗管线拒绝与空流() {
        WindowPort.InMemoryWindowEngine engine = new WindowPort.InMemoryWindowEngine();
        assertThrows(IllegalArgumentException.class, () -> engine.process(List.of(),
                new WindowAssigner(10_000L, 5_000L), new WatermarkTracker(0L, () -> 0L)),
                "组合管线要求滚动窗");
        assertTrue(engine.process(List.of(), new WindowAssigner(10_000L, 0),
                new WatermarkTracker(0L, () -> 0L)).isEmpty(), "空流零结果");
    }
}

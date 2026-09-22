package cn.chyuan.ai.domain.windowkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流窗口内核 BO1-BO7 单测（工单 0557-0563）：
 * watermark/乱序迟到/滚动滑动/会话窗口/触发累加/键控状态/拓扑算子链。
 */
class WindowKernelTest {

    @Test
    void BO1_watermark单调推进与回退拒绝() {
        long[] now = {1000L};
        WatermarkTracker tracker = new WatermarkTracker(5000L, () -> now[0]);
        assertEquals(Long.MIN_VALUE, tracker.watermark(), "未上报无 watermark");
        assertEquals(1000L, tracker.processingTime(), "时钟端口处理时间");
        tracker.observe(10_000L);
        assertEquals(5000L, tracker.watermark(), "watermark=最大事件时间-乱序延迟");
        tracker.observe(12_000L);
        assertEquals(7000L, tracker.watermark());
        tracker.observe(11_000L);
        assertEquals(7000L, tracker.watermark(), "乱序事件合法且不回退 watermark");
        assertTrue(tracker.isLate(6_000L), "早于 watermark 判迟到");
        assertFalse(tracker.isLate(7_000L));
        assertTrue(tracker.firesWindowEnd(7000L), "watermark 越过窗尾判定");
        assertFalse(tracker.firesWindowEnd(7001L));
        assertThrows(IllegalArgumentException.class, () -> new WatermarkTracker(-1L, () -> now[0]),
                "负乱序延迟拒绝");
        assertThrows(IllegalArgumentException.class, () -> new WatermarkTracker(1L, null), "null 时钟拒绝");
    }

    @Test
    void BO2_迟到事件旁路输出() {
        long[] wm = {15_000L};
        LateEventBuffer buffer = new LateEventBuffer(2_000L, () -> wm[0]);
        WindowAssigner assigner = new WindowAssigner(10_000L, 0);
        WindowAssigner.Window window = assigner.assignTumbling(12_000L);
        assertEquals(new WindowAssigner.Window(10_000L, 20_000L), window);
        // watermark=15s < 窗尾20s-宽限2s=18s → 正常
        LateEventBuffer.Outcome normal = buffer.onEvent(new WatermarkTracker.Event(12_000L, "a", 1), window);
        assertTrue(normal.accepted());
        // watermark=19s ∈ [18s,20s) → 宽限二次触发窗
        wm[0] = 19_000L;
        assertTrue(buffer.onEvent(new WatermarkTracker.Event(11_000L, "a", 1), window).accepted());
        // watermark=21s ≥ 窗尾 → 迟到旁路
        wm[0] = 21_000L;
        LateEventBuffer.Outcome late = buffer.onEvent(new WatermarkTracker.Event(12_000L, "a", 1), window);
        assertFalse(late.accepted());
        assertTrue(late.sideOutput());
        assertEquals(1, buffer.lateCount());
        List<WatermarkTracker.Event> events = List.of(
                new WatermarkTracker.Event(12_000L, "a", 1),
                new WatermarkTracker.Event(12_500L, "a", 1));
        assertEquals(2, buffer.sideOutput(events, e -> assigner.assignTumbling(e.eventTime())).size(),
                "旁路批量收集");
    }

    @Test
    void BO3_滚动与滑动窗口归属() {
        WindowAssigner tumbling = new WindowAssigner(10_000L, 0);
        assertEquals(new WindowAssigner.Window(10_000L, 20_000L), tumbling.assignTumbling(15_000L));
        assertEquals(new WindowAssigner.Window(10_000L, 20_000L), tumbling.assignTumbling(10_000L),
                "边界左闭右含起点");
        assertEquals(new WindowAssigner.Window(0L, 10_000L), tumbling.assignTumbling(9_999L));
        WindowAssigner sliding = new WindowAssigner(10_000L, 5_000L);
        List<WindowAssigner.Window> windows = sliding.assignSliding(12_000L);
        assertEquals(List.of(new WindowAssigner.Window(5_000L, 15_000L),
                new WindowAssigner.Window(10_000L, 20_000L)), windows, "滑动一事件多窗归属");
        List<WatermarkTracker.Event> events = List.of(
                new WatermarkTracker.Event(1_000L, "a", 1),
                new WatermarkTracker.Event(9_000L, "a", 1),
                new WatermarkTracker.Event(11_000L, "a", 1));
        assertEquals(2, tumbling.countsByTumblingWindow(events).size(), "两窗计数");
        assertEquals(2, tumbling.countsByTumblingWindow(events).get(0L));
        assertThrows(IllegalArgumentException.class, () -> new WindowAssigner(0, 0), "零窗长拒绝");
        assertThrows(IllegalArgumentException.class, () -> new WindowAssigner(10_000L, 20_000L),
                "步长超窗长拒绝");
    }

    @Test
    void BO4_会话窗口gap合并() {
        SessionWindows sessions = new SessionWindows(5_000L);
        List<WatermarkTracker.Event> events = List.of(
                new WatermarkTracker.Event(1_000L, "a", 1),
                new WatermarkTracker.Event(4_000L, "a", 1),
                new WatermarkTracker.Event(8_000L, "a", 1),
                new WatermarkTracker.Event(60_000L, "a", 1));
        List<WindowAssigner.Window> windows = sessions.assign(events);
        assertEquals(2, windows.size(), "1-8s 同会话，60s 新会话");
        assertEquals(new WindowAssigner.Window(1_000L, 8_001L), windows.get(0));
        assertEquals(new WindowAssigner.Window(60_000L, 60_001L), windows.get(1));
        assertTrue(SessionWindows.disjointAscending(windows), "会话窗互叠不允许");
        assertEquals(0, sessions.assign(List.of()).size(), "空事件零窗");
        var byKey = sessions.assignByKey(List.of(
                new WatermarkTracker.Event(1_000L, "k1", 1),
                new WatermarkTracker.Event(1_000L, "k2", 1)));
        assertEquals(1, byKey.get("k1").size());
        assertThrows(IllegalArgumentException.class, () -> new SessionWindows(-1L), "负 gap 拒绝");
    }

    @Test
    void BO5_触发器累加与撤回一致性() {
        WindowAssigner.Window window = new WindowAssigner.Window(0L, 10_000L);
        WindowTrigger accumulating = new WindowTrigger(2, WindowTrigger.Mode.ACCUMULATING);
        List<WindowTrigger.TriggerOutput> accOutputs = accumulating.replay(window, List.of(10.0d, 20.0d, 30.0d), true);
        assertEquals(2, accOutputs.size(), "计数阈值 2 触发一次+watermark 终触发一次");
        assertEquals(30.0d, accOutputs.get(0).value(), "累加模式输出全量");
        assertEquals(60.0d, accOutputs.get(1).value(), "终触发累计 10+20+30");
        assertEquals(60.0d, WindowTrigger.accumulatingFinal(accOutputs), "最终值=最后一次全量");
        WindowTrigger retract = new WindowTrigger(2, WindowTrigger.Mode.RETRACT);
        List<WindowTrigger.TriggerOutput> retOutputs = retract.replay(window, List.of(10.0d, 20.0d, 30.0d), true);
        assertEquals(2, retOutputs.size());
        assertEquals(30.0d, retOutputs.get(0).value(), "首次撤回模式增量=全量");
        assertEquals(30.0d, retOutputs.get(1).value(), "第二次增量=60-30");
        assertEquals(30.0d, retOutputs.get(1).retractedPrevious(), "撤回记录上一次值");
        assertEquals(60.0d, WindowTrigger.retractAccumulates(retOutputs), "增量累加=全量一致");
        assertThrows(IllegalArgumentException.class, () -> new WindowTrigger(0, WindowTrigger.Mode.RETRACT),
                "零阈值拒绝");
    }

    @Test
    void BO6_键控状态TTL清理() {
        long[] now = {0L};
        KeyedWindowState state = new KeyedWindowState(10_000L, () -> now[0]);
        WindowAssigner.Window w1 = new WindowAssigner.Window(0L, 10_000L);
        WindowAssigner.Window w2 = new WindowAssigner.Window(10_000L, 20_000L);
        state.add("a", w1, 1.0d);
        state.add("a", w1, 2.0d);
        state.add("a", w2, 5.0d);
        state.add("b", w1, 7.0d);
        assertEquals(3.0d, state.get("a", w1).sum(), "同键同窗累计 1+2");
        assertEquals(5.0d, state.get("a", w2).sum(), "同键异窗互不可见");
        assertEquals(7.0d, state.get("b", w1).sum(), "异键同窗互不可见");
        assertEquals(2, state.keyCount());
        assertEquals(3, state.stateCount());
        now[0] = 11_000L;
        assertEquals(3, state.evictExpired(), "全部超龄逐出");
        assertEquals(0.0d, state.get("a", w1).sum(), "过期视为缺失");
        assertEquals(0, state.keyCount(), "空键桶回收");
        assertEquals(3, state.evictedStates());
        assertThrows(IllegalArgumentException.class, () -> new KeyedWindowState(0L, () -> now[0]),
                "零 TTL 拒绝");
    }

    @Test
    void BO7_拓扑校验与算子链() {
        Topology topo = new Topology()
                .add(new Topology.Operator("src", Topology.OpType.SOURCE, 1))
                .add(new Topology.Operator("win", Topology.OpType.WINDOW_AGG, 1))
                .add(new Topology.Operator("win2", Topology.OpType.WINDOW_AGG, 1))
                .add(new Topology.Operator("sink", Topology.OpType.SINK, 1))
                .connect("src", "win")
                .connect("win", "win2")
                .connect("win2", "sink");
        topo.validate();
        assertEquals(3, topo.chained().size(), "同类型同并行度合并：src | win+win2 | sink");
        assertEquals("src(SOURCE/1) -> win\nwin(WINDOW_AGG/1) -> win2\nwin2(WINDOW_AGG/1) -> sink\nsink(SINK/1)",
                topo.describe(), "拓扑文本化");
        Topology noSink = new Topology()
                .add(new Topology.Operator("src", Topology.OpType.SOURCE, 1));
        assertThrows(IllegalArgumentException.class, noSink::validate, "无 SINK 拒绝");
        Topology cyclic = new Topology()
                .add(new Topology.Operator("src", Topology.OpType.SOURCE, 1))
                .add(new Topology.Operator("win", Topology.OpType.WINDOW_AGG, 1))
                .add(new Topology.Operator("sink", Topology.OpType.SINK, 1))
                .connect("src", "win")
                .connect("win", "sink")
                .connect("sink", "src");
        assertThrows(IllegalArgumentException.class, cyclic::validate, "有环拒绝");
        Topology orphan = new Topology()
                .add(new Topology.Operator("src", Topology.OpType.SOURCE, 1))
                .add(new Topology.Operator("win", Topology.OpType.WINDOW_AGG, 1))
                .add(new Topology.Operator("sink", Topology.OpType.SINK, 1))
                .connect("src", "sink");
        assertThrows(IllegalArgumentException.class, orphan::validate, "孤立算子拒绝");
        assertThrows(IllegalArgumentException.class, () -> new Topology.Operator("x",
                Topology.OpType.SOURCE, 0), "并行度约束");
    }
}

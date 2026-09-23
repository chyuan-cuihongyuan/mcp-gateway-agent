package cn.chyuan.ai.domain.machinekernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 状态机内核域单测（工单 0618-0624 BV1-BV7，XState 思想）。
 * 迁移表/守卫动作/层级冒泡/并行与最终态/延迟迁移/invoke actor/快照恢复。
 */
class MachineKernelTest {

    @Test
    void flatTransitionsDispatchAndRejectUndefined() {
        Statechart chart = Statechart.builder("off")
                .state("off")
                .state("on")
                .transition("off", "TOGGLE", "on")
                .transition("on", "TOGGLE", "off")
                .build();
        Statechart.Interpreter machine = chart.start();
        assertEquals(Set.of("off"), machine.activeLeaves());
        machine.send("TOGGLE");
        assertEquals(Set.of("on"), machine.activeLeaves());
        machine.send("TOGGLE");
        assertEquals(Set.of("off"), machine.activeLeaves());
        assertThrows(IllegalArgumentException.class, () -> machine.send("UNKNOWN"));

        Statechart lenient = Statechart.builder("off")
                .state("off").state("on")
                .undefinedPolicy(Statechart.UndefinedPolicy.IGNORE)
                .transition("off", "TOGGLE", "on")
                .build();
        Statechart.Interpreter m2 = lenient.start();
        assertTrue(m2.send("UNKNOWN").isEmpty(), "自环忽略策略");
        assertEquals(Set.of("off"), m2.activeLeaves());
    }

    @Test
    void guardsFallThroughAndActionsRunInOrder() {
        Statechart chart = Statechart.builder("idle")
                .state("idle").state("slow").state("fast")
                .transition("idle", "GO", "fast",
                        ctx -> Integer.parseInt(String.valueOf(ctx.getOrDefault("speed", "0"))) >= 10, "act:fast")
                .transition("idle", "GO", "slow", null, "act:slow")
                .onEntry("fast", "enter:fast")
                .onExit("idle", "exit:idle")
                .build();
        Statechart.Interpreter machine = chart.start();
        List<String> actions = machine.send("GO", Map.of("speed", 3));
        assertEquals(Set.of("slow"), machine.activeLeaves(), "低速走第二候选");
        assertTrue(actions.contains("exit:idle"));
        assertTrue(actions.contains("act:slow"));
        int exitIdx = actions.indexOf("exit:idle");
        int actIdx = actions.indexOf("act:slow");
        assertTrue(exitIdx < actIdx, "exit 在 transition 动作之前");

        Statechart.Interpreter fast = chart.start();
        fast.send("GO", Map.of("speed", 30));
        assertEquals(Set.of("fast"), fast.activeLeaves());
        assertTrue(fast.actionLog().contains("enter:fast"));
    }

    @Test
    void hierarchicalStatesBubbleEventsAndOrderEntryExit() {
        Statechart chart = Statechart.builder("parent")
                .composite("parent", "childA")
                .state("childA", "parent").state("childB", "parent")
                .state("other")
                .transition("childA", "STEP", "childB", null, "act:child-step")
                .transition("parent", "RESET", "other", null, "act:parent-reset")
                .onEntry("parent", "enter:parent")
                .onExit("parent", "exit:parent")
                .build();
        Statechart.Interpreter machine = chart.start();
        assertEquals(List.of("parent", "childA"), machine.pathTo("childA"), "复合态进入落初始子态");
        machine.send("STEP");
        assertEquals(Set.of("childB"), machine.activeLeaves());

        List<String> actions = machine.send("RESET");
        assertEquals(Set.of("other"), machine.activeLeaves(), "子态无迁移时冒泡到父");
        assertTrue(actions.contains("exit:parent"));
        assertTrue(actions.contains("act:parent-reset"));
        int exitIdx = actions.indexOf("exit:parent");
        int actIdx = actions.indexOf("act:parent-reset");
        assertTrue(exitIdx < actIdx, "退出先深后浅：父退出在迁移动作前");
    }

    @Test
    void parallelRegionsConvergeOnFinalToDone() {
        Statechart chart = Statechart.builder("running")
                .parallel("running", List.of("left", "right"))
                .composite("left", "leftWork")
                .composite("right", "rightWork")
                .state("leftWork").state("rightWork")
                .finalState("leftDone", "left").finalState("rightDone", "right")
                .finalState("allDone")
                .transition("leftWork", "LEFT", "leftDone")
                .transition("rightWork", "RIGHT", "rightDone")
                .transition("running", "done.running", "allDone", null, "act:parallel-complete")
                .build();
        Statechart.Interpreter machine = chart.start();
        assertEquals(Set.of("leftWork", "rightWork"), machine.activeLeaves(), "两区域同时活跃");
        machine.send("LEFT");
        assertEquals(Set.of("leftDone", "rightWork"), machine.activeLeaves(), "区域迁移互不干扰");
        assertFalse(machine.isFinal("allDone"));

        machine.send("RIGHT");
        assertEquals(Set.of("allDone"), machine.activeLeaves(), "区域全终触发 done 迁移");
        assertTrue(machine.actionLog().contains("act:parallel-complete"));
        assertTrue(machine.isFinal("allDone"));
    }

    @Test
    void afterTimersFireEarliestAndCancelOnEvent() {
        StringBuilder log = new StringBuilder();
        AfterTimers timers = new AfterTimers(
                (state, event) -> log.append(state).append('@').append(event).append(';'),
                List.of(new AfterTimers.Delayed("waiting", 100, "GO"),
                        new AfterTimers.Delayed("waiting", 500, "LATE"),
                        new AfterTimers.Delayed("idle", 50, "WAKE")));
        timers.onEnter("waiting", 0);
        assertEquals(2, timers.pending());
        timers.onEvent("waiting");
        assertEquals(0, timers.pending(), "事件到达取消未决超时");

        timers.onEnter("waiting", 0);
        timers.onEnter("idle", 30);
        List<String> fired = timers.advance(120);
        assertEquals(List.of("idle#WAKE", "waiting#GO"), fired, "按到期序触发");
        assertEquals("idle@WAKE;waiting@GO;", log.toString());
        assertTrue(timers.advance(499).isEmpty(), "未到期不触发");
        assertEquals(List.of("waiting#LATE"), timers.advance(500), "到期触发");
        assertThrows(IllegalArgumentException.class, () -> timers.advance(1));
        assertThrows(IllegalArgumentException.class,
                () -> new AfterTimers((s, e) -> { }, List.of(new AfterTimers.Delayed("s", 0, "E"))));
    }

    @Test
    void invokedActorsMapDoneAndErrorAndCancelOnExit() {
        ActorRunner runner = new ActorRunner((actorId, payload) -> { });
        runner.onState("fetching", "fetcher");
        runner.onEnter("fetching", Map.of("url", "x"));
        assertTrue(runner.isPending("fetcher"));
        assertEquals("done.invoke.fetcher", runner.complete("fetcher", Map.of("bytes", 10)));
        assertTrue(runner.completedLog().contains("fetcher"));

        runner.onEnter("fetching", Map.of());
        runner.onExit("fetching");
        assertFalse(runner.isPending("fetcher"), "退出即取消");
        assertThrows(IllegalArgumentException.class, () -> runner.complete("fetcher", "late"));
        runner.onEnter("fetching", Map.of());
        assertEquals("error.invoke.fetcher", runner.fail("fetcher", "boom"));
        assertTrue(runner.failedLog().contains("fetcher:boom"));
    }

    @Test
    void snapshotsRoundTripAndRestoreContinues() {
        Statechart chart = Statechart.builder("idle")
                .state("idle").state("ready").state("done")
                .transition("idle", "GO", "ready")
                .transition("ready", "DONE", "done")
                .build();
        Statechart.Interpreter machine = chart.start();
        machine.send("GO");
        machine.context().put("taskId", "t-1");
        assertEquals(1L, machine.entryCount("ready"));

        String serialized = MachineSnapshots.serialize(machine.snapshot());
        assertTrue(serialized.contains("active:"));
        assertTrue(serialized.contains("ctx:taskId=t-1"));

        MachineSnapshots.Snapshot parsed = MachineSnapshots.deserialize(serialized);
        assertEquals(machine.activeLeaves().size(), parsed.activeStates().size());
        assertEquals("t-1", parsed.context().get("taskId"));

        Statechart.Interpreter restored = chart.start();
        restored.restore(parsed);
        assertEquals(Set.of("ready"), restored.activeLeaves());
        restored.send("DONE");
        assertEquals(Set.of("done"), restored.activeLeaves(), "恢复后继续接受事件");
    }
}

package cn.chyuan.ai.domain.machinekernel.service;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 状态机端口组合管线测试（工单 0625 BV8）。
 * machine-kernel.enabled 默认关（开启才改变行为）；
 * 与 session·generation 只读联动：长任务状态迁移校验可选形态。
 */
class MachinePortPipelineTest {

    @Test
    void longTaskLifecycleRunsParallelRegionsToDone() {
        MachinePort port = new MachinePort.InMemoryMachines();
        Statechart.Interpreter machine = port.longTaskMachine(Map.of("taskId", "task-9"));
        assertEquals(Set.of("idle"), machine.activeLeaves());

        assertEquals(Set.of("downloading", "parsing"), port.send(machine, MachinePort.EVENT_START));
        assertFalse(port.isTerminal(machine));

        port.send(machine, MachinePort.EVENT_DOWNLOADED);
        assertTrue(machine.activeLeaves().contains("downloadDone"));

        assertEquals(Set.of("done"), port.send(machine, MachinePort.EVENT_PARSED));
        assertTrue(port.isTerminal(machine));
        assertTrue(machine.actionLog().contains("act:complete"));
        assertTrue(machine.actionLog().contains("act:downloaded"));
    }

    @Test
    void failuresShortCircuitToErrorFromEitherRegion() {
        MachinePort port = new MachinePort.InMemoryMachines();
        Statechart.Interpreter machine = port.longTaskMachine(Map.of());
        port.send(machine, MachinePort.EVENT_START);
        assertEquals(Set.of("error"), port.send(machine, MachinePort.EVENT_FAIL));
        assertTrue(port.isTerminal(machine));
        assertTrue(machine.actionLog().contains("act:fail-short"));

        Statechart.Interpreter timedOut = port.longTaskMachine(Map.of());
        port.send(timedOut, MachinePort.EVENT_START);
        assertEquals(Set.of("error"), port.send(timedOut, MachinePort.EVENT_TIMEOUT));
        assertTrue(timedOut.actionLog().contains("act:timeout"));
    }

    @Test
    void terminalMachinesRejectFurtherEvents() {
        MachinePort port = new MachinePort.InMemoryMachines();
        Statechart.Interpreter machine = port.longTaskMachine(Map.of());
        port.send(machine, MachinePort.EVENT_START);
        port.send(machine, MachinePort.EVENT_FAIL);
        assertThrows(IllegalArgumentException.class,
                () -> port.send(machine, MachinePort.EVENT_DOWNLOADED), "error 为终态拒绝后续事件");
    }

    @Test
    void snapshotExportAndRestoreContinuesLifecycle() {
        MachinePort port = new MachinePort.InMemoryMachines();
        Statechart.Interpreter machine = port.longTaskMachine(Map.of("sessionId", "sess-42"));
        port.send(machine, MachinePort.EVENT_START);
        port.send(machine, MachinePort.EVENT_DOWNLOADED);

        String serialized = port.exportSnapshot(machine);
        assertTrue(serialized.contains("ctx:sessionId=sess-42"));

        Statechart.Interpreter restored = port.restoreMachine(serialized);
        assertTrue(restored.activeLeaves().contains("downloadDone"));
        assertTrue(restored.activeLeaves().contains("parsing"));
        assertEquals(Set.of("done"), port.send(restored, MachinePort.EVENT_PARSED));
        assertTrue(port.isTerminal(restored));
    }
}

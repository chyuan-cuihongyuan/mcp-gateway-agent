package cn.chyuan.ai.domain.schedkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SchedPort 组合管线测试（工单 0739 CI8，golang GMP 思想）。
 * go/step/runUntilIdle 统一编排/machinekernel 状态机形状只读联动（G 状态快照，泛型入参不 import）/
 * sched-kernel.enabled 默认关。
 */
class SchedPortPipelineTest {

    @Test
    void portGoRunSnapshotAndStats() {
        SchedPort port = SchedPort.inMemory(2);
        port.go(0, "api", List.of("parse", "call", "render"));
        port.go(1, "db", List.of("query"));
        port.runUntilIdle();

        List<String> snapshot = port.gStateSnapshot();
        assertEquals(2, snapshot.size());
        assertTrue(snapshot.contains("api:DEAD"));
        assertTrue(snapshot.contains("db:DEAD"));
        assertTrue(port.statsLine().startsWith("created=2 finished=2"), "统计行形状");
        assertTrue(port.statsLine().contains("finished=2"));
    }

    @Test
    void portStepwiseMidFlightState() {
        SchedPort port = SchedPort.inMemory(1);
        long id = port.go(0, "task", List.of("s1", "s2", "s3", "s4", "s5"));
        assertTrue(id > 0);
        port.step(2);
        assertTrue(port.gStateSnapshot().contains("task:RUNNING"), "步进中处于 RUNNING");
        port.step(10);
        assertTrue(port.gStateSnapshot().contains("task:DEAD"));
        assertThrows(IllegalArgumentException.class, () -> port.go(0, "empty", List.of()), "空任务拒绝");
    }

    @Test
    void portStateShapeForMachineKernelLinkage() {
        // machinekernel 只读联动形态：状态机形状数据（name:STATE 字符串，泛型入参不 import）
        SchedPort port = SchedPort.inMemory(1);
        port.go(0, "machine-shape", List.of("a", "b"));
        port.step(1);
        for (String state : port.gStateSnapshot()) {
            assertTrue(state.matches("[\\w-]+:(RUNNABLE|RUNNING|WAITING|DEAD)"), "状态形状合法: " + state);
        }
        assertTrue(port.gStateSnapshot().contains("machine-shape:RUNNING"));
    }
}

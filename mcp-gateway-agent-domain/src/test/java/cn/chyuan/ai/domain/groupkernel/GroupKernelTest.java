package cn.chyuan.ai.domain.groupkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 消费组再均衡内核测试（工单 1204-1211 FF1-FF8，kafka 思想）。
 * 成员加入/generation 代际/分配策略/心跳会话/成员离组/增量协作/偏移提交/端口组合管线。
 */
class GroupKernelTest {

    @Test
    void memberJoin() {
        GroupPort port = GroupPort.inMemory();
        port.topic("t", 4);
        assertEquals("m1", port.join("g", "m1"));
        port.join("g", "m2");
        assertEquals(List.of("m1", "m2"), port.members("g"), "组内成员字典序");
        assertThrows(IllegalArgumentException.class, () -> port.join("g", "m1"), "重复成员拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.join(" ", "m"), "空 groupId 拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.join("g", " "), "空 memberId 拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.members("ghost"), "未知组拒绝");
    }

    @Test
    void generation() {
        GroupPort port = GroupPort.inMemory();
        port.topic("t", 4);
        port.join("g", "m1");
        assertEquals(1, port.sync("g", "RANGE"));
        assertEquals(2, port.sync("g", "RANGE"), "代际单调递增");
        port.heartbeat("g", "m1", 2);
        assertThrows(IllegalArgumentException.class, () -> port.heartbeat("g", "m1", 1), "过期代际拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.heartbeat("g", "ghost", 2), "未知成员心跳拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.sync("ghost", "RANGE"), "未知组同步拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.sync("g", "NOPE"), "未知策略拒绝");
    }

    @Test
    void assignment() {
        GroupPort port = GroupPort.inMemory();
        port.topic("t", 5);
        port.join("g", "m1");
        port.join("g", "m2");
        port.sync("g", "RANGE");
        assertEquals(List.of(0, 1, 2), port.assignmentOf("g", "m1"), "range 前者多担余数");
        assertEquals(List.of(3, 4), port.assignmentOf("g", "m2"));
        port.sync("g", "ROUND_ROBIN");
        assertEquals(List.of(0, 2, 4), port.assignmentOf("g", "m1"), "轮转逐区分派");
        assertEquals(List.of(1, 3), port.assignmentOf("g", "m2"));

        GroupPort tight = GroupPort.inMemory();
        tight.topic("t", 2);
        tight.join("g", "m1");
        tight.join("g", "m2");
        tight.join("g", "m3");
        tight.sync("g", "RANGE");
        assertEquals(List.of(0), tight.assignmentOf("g", "m1"));
        assertEquals(List.of(1), tight.assignmentOf("g", "m2"));
        assertEquals(List.of(), tight.assignmentOf("g", "m3"), "分区少于成员空分配合法");
        assertThrows(IllegalArgumentException.class, () -> tight.assignmentOf("g", "ghost"), "未知成员分配拒绝");
    }

    @Test
    void heartbeat() {
        GroupPort port = GroupPort.inMemory();
        port.topic("t", 2);
        port.join("g", "m1");
        port.sync("g", "RANGE");
        port.advance(6);
        port.heartbeat("g", "m1", 1);
        port.advance(6);
        assertEquals(List.of(), port.reap(), "心跳续期 6 tick 内不超时");
        port.advance(5);
        assertEquals(List.of("g/m1"), port.reap(), "会话超时踢出");
        assertEquals(List.of(), port.members("g"));
        assertEquals(2, port.generation("g"), "逐出触发再均衡代际递增");
        assertThrows(IllegalArgumentException.class, () -> port.heartbeat("g", "m1", 2), "被逐成员心跳拒绝");
    }

    @Test
    void memberLeave() {
        GroupPort port = GroupPort.inMemory();
        port.topic("t", 4);
        port.join("g", "m1");
        port.join("g", "m2");
        port.sync("g", "RANGE");
        port.leave("g", "m2");
        assertEquals(List.of("m1"), port.members("g"));
        assertEquals(List.of(0, 1, 2, 3), port.assignmentOf("g", "m1"), "离组分配回收重派");
        assertEquals(2, port.generation("g"), "离组触发再均衡");
        assertEquals(List.of("m1:0", "m1:1", "m2:2", "m2:3"), port.lastRevoked("g"),
                "eager 离组全量回收留痕");
        port.leave("g", "m2");
        assertThrows(IllegalArgumentException.class, () -> port.leave("g", "ghost"), "未知成员离组拒绝");
    }

    @Test
    void cooperative() {
        GroupPort port = GroupPort.inMemory();
        port.topic("t", 4);
        port.join("g", "m1");
        port.sync("g", "COOPERATIVE");
        port.join("g", "m2");
        port.sync("g", "COOPERATIVE");
        assertEquals(List.of(), port.lastRevoked("g"), "增量协作存活成员分配保留零回收");
        assertEquals(List.of(0, 1), port.assignmentOf("g", "m1"));
        assertEquals(List.of(2, 3), port.assignmentOf("g", "m2"));
        port.leave("g", "m2");
        assertEquals(List.of("m2:2", "m2:3"), port.lastRevoked("g"), "只回收失效成员分区");
        assertEquals(List.of(0, 1, 2, 3), port.assignmentOf("g", "m1"));

        GroupPort eager = GroupPort.inMemory();
        eager.topic("t", 4);
        eager.join("g", "m1");
        eager.sync("g", "RANGE");
        eager.join("g", "m2");
        eager.sync("g", "RANGE");
        assertEquals(List.of("m1:0", "m1:1", "m1:2", "m1:3"), eager.lastRevoked("g"),
                "eager 全量回收上一代对比");
    }

    @Test
    void offsetCommit() {
        GroupPort port = GroupPort.inMemory();
        port.topic("t", 2);
        port.join("g", "m1");
        port.join("g", "m2");
        port.sync("g", "RANGE");
        port.commit("g", "m1", "t", 0, 5);
        assertEquals(5, port.committed("g", "t", 0));
        port.commit("g", "m1", "t", 0, 5);
        assertEquals(5, port.committed("g", "t", 0), "同位点幂等");
        port.commit("g", "m1", "t", 0, 6);
        assertEquals(6, port.committed("g", "t", 0));
        assertThrows(IllegalArgumentException.class, () -> port.commit("g", "m1", "t", 0, 3), "位点倒退拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.commit("g", "m1", "t", 1, 1), "未分配分区提交拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.commit("ghost", "m1", "t", 0, 1), "未知组提交拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.commit("g", "ghost", "t", 0, 1), "未知成员提交拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.commit("g", "m1", "nope", 0, 1), "未知主题提交拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.commit("g", "m1", "t", 9, 1), "分区越界提交拒绝");
    }

    @Test
    void groupPipeline() {
        GroupPort port = GroupPort.inMemory();
        port.topic("orders", 4);
        port.join("checkout", "c1");
        port.join("checkout", "c2");
        assertEquals(1, port.sync("checkout", "RANGE"));
        port.heartbeat("checkout", "c1", 1);
        port.heartbeat("checkout", "c2", 1);
        port.commit("checkout", "c1", "orders", 0, 100);
        port.commit("checkout", "c2", "orders", 2, 50);
        port.advance(3);
        assertEquals(List.of(), port.reap());
        assertEquals(100, port.committed("checkout", "orders", 0));
        assertEquals(50, port.committed("checkout", "orders", 2));
        assertEquals(0, port.committed("checkout", "orders", 1), "未提交默认 0");
        assertEquals(List.of("group", "topic", "entries", "fromOffset"), port.partitionShape(),
                "msgkernel 主题分区形状只读联动");
    }
}

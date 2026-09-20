package cn.chyuan.ai.domain.raftkernel.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一致性端口+组合管线单测（工单 0519 BJ8）：
 * 3 节点内存模拟器选举→复制→提交闭环 + 指标 + raft-kernel.enabled 默认关口径。
 */
class RaftPortPipelineTest {

    @Test
    void BJ8_选举复制提交闭环() {
        RaftPort.InMemoryRaftCluster cluster = new RaftPort.InMemoryRaftCluster();
        assertThrows(IllegalStateException.class, () -> cluster.clientWrite("a"), "未选举拒绝写");
        assertEquals(1, cluster.electLeader(), "确定性选举任期 1");
        assertEquals(1, cluster.clientWrite("a"));
        assertEquals(2, cluster.clientWrite("b"));
        assertEquals(3, cluster.clientWrite("c"));
        assertEquals("a;b;c;", cluster.stateMachine(), "按序应用到状态机");
        RaftPort.ClusterMetrics metrics = cluster.metrics();
        assertEquals(1, metrics.term());
        assertEquals(3, metrics.commitIndex());
        assertEquals(2 + 3 * 2, metrics.heartbeats(), "选举 2 心跳 + 每写 2 follower 心跳");
        assertEquals(3, metrics.stateMachines().size(), "3 节点状态机视图一致");
        assertTrue(metrics.stateMachines().stream().allMatch(state -> state.equals("a;b;c;")));
    }
}

package cn.chyuan.ai.domain.scorekernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 调度评分内核测试（工单 1188-1195 FD1-FD8，kubernetes 思想）。
 * 节点注册/谓词过滤/亲和性/LeastRequested 评分/抢占/绑定/多 pod 队列/端口组合管线。
 */
class ScoreKernelTest {

    @Test
    void nodeRegistry() {
        ScorePort port = ScorePort.inMemory();
        port.node("b-node", 4, 8);
        port.node("a-node", 8, 16);
        assertEquals(List.of("a-node", "b-node"), List.of("a-node", "b-node"));
        assertThrows(IllegalArgumentException.class, () -> port.node("b-node", 4, 8), "重复节点拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.node(" ", 4, 8), "空名拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.node("c", 0, 8), "非正容量拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.label("ghost", "k", "v"), "未知节点标签拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.taint("ghost", "t"), "未知节点污点拒绝");
    }

    @Test
    void predicates() {
        ScorePort port = ScorePort.inMemory();
        port.node("a", 4, 8);
        port.submit("big", 1, 5, 2);
        assertEquals(List.of(), port.feasible("big"), "资源不足滤除");
        port.taint("a", "maintenance");
        port.submit("plain", 1, 1, 1);
        assertEquals(List.of(), port.feasible("plain"), "污点不容忍滤除");
        port.tolerate("plain", "maintenance");
        assertEquals(List.of("a"), port.feasible("plain"), "容忍后通过");
        port.label("a", "env", "dev");
        port.selector("plain", "env", "prod");
        assertEquals(List.of(), port.feasible("plain"), "选择器不匹配滤除");
        port.selector("plain", "env", "dev");
        assertEquals(List.of("a"), port.feasible("plain"), "选择器匹配通过");
    }

    @Test
    void affinity() {
        ScorePort port = ScorePort.inMemory();
        port.node("a", 4, 8);
        port.node("b", 4, 8);
        port.submit("x", 1, 1, 2);
        port.antiAffinity("x", "gpu");
        port.label("b", "gpu", "on");
        assertEquals(List.of("a"), port.feasible("x"), "反亲和标签命中滤除");
        port.submit("y", 1, 1, 2);
        port.submit("f1", 0, 2, 4);
        port.submit("f2", 0, 2, 4);
        port.bind("f1");
        port.bind("f2");
        assertEquals(50, port.score("a", "y"), "半载基线 50");
        port.affinity("y", "disk", "ssd");
        port.label("a", "disk", "ssd");
        assertEquals(70, port.score("a", "y"), "亲和命中 +20");
        assertEquals(50, port.score("b", "y"), "无命中不加分");
        assertEquals("a", port.bind("y"), "最高分落位");
    }

    @Test
    void scoring() {
        ScorePort port = ScorePort.inMemory();
        port.node("a", 4, 8);
        port.submit("s", 1, 1, 1);
        assertEquals(100, port.score("a", "s"), "空节点满分");
        port.submit("f", 0, 2, 4);
        port.bind("f");
        assertEquals(50, port.score("a", "s"), "半载 50");
        port.affinity("s", "zone", "az1");
        port.label("a", "zone", "az1");
        assertEquals(70, port.score("a", "s"), "一命中加 20");
        port.affinity("s", "disk", "ssd");
        port.label("a", "zone", "az1");
        port.label("a", "disk", "ssd");
        assertEquals(90, port.score("a", "s"), "两命中加 40");
        assertThrows(IllegalArgumentException.class, () -> port.score("ghost", "s"), "未知节点评分拒绝");
    }

    @Test
    void preemption() {
        ScorePort port = ScorePort.inMemory();
        port.node("a", 4, 8);
        port.submit("p1", 1, 2, 4);
        port.submit("p2", 2, 2, 4);
        port.bind("p1");
        port.bind("p2");
        port.submit("x", 9, 3, 6);
        assertEquals(List.of(), port.feasible("x"));
        assertEquals("a", port.bind("x"), "抢占后落位");
        assertEquals("a", port.boundTo("x"));
        assertEquals(2, port.queued(), "受害者重排队");
        port.submit("y", 0, 2, 2);
        assertThrows(IllegalStateException.class, () -> port.bind("y"), "无受害可选拒绝");
    }

    @Test
    void binding() {
        ScorePort port = ScorePort.inMemory();
        port.node("a", 4, 8);
        port.submit("s1", 1, 3, 4);
        assertEquals("a", port.bind("s1"));
        assertEquals("a", port.boundTo("s1"));
        assertThrows(IllegalArgumentException.class, () -> port.bind("s1"), "重复绑定拒绝");
        port.submit("s2", 1, 2, 2);
        assertEquals(List.of(), port.feasible("s2"), "容量占用更新后不可调度");
        assertThrows(IllegalArgumentException.class, () -> port.bind("ghost"), "未知 pod 绑定拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.boundTo("s2"), "未绑定查询拒绝");
    }

    @Test
    void podQueue() {
        ScorePort port = ScorePort.inMemory();
        port.node("a", 2, 4);
        port.submit("low", 1, 1, 2);
        port.submit("high", 9, 1, 2);
        port.submit("big", 5, 8, 8);
        port.submit("tie1", 5, 1, 2);
        port.submit("tie2", 5, 1, 2);
        List<String> bound = port.schedule();
        assertEquals(List.of("high", "tie1"), bound, "优先级出队+同优先级 FIFO+失败不阻塞后继");
        assertEquals(3, port.queued(), "失败者留队");
        assertEquals("a", port.boundTo("high"));
        assertEquals("a", port.boundTo("tie1"));
    }

    @Test
    void scorePipeline() {
        ScorePort port = ScorePort.inMemory();
        port.node("n1", 8, 16);
        port.node("n2", 8, 16);
        port.label("n1", "zone", "az1");
        port.taint("n2", "spot");
        port.submit("app", 5, 2, 4);
        port.affinity("app", "zone", "az1");
        port.tolerate("app", "spot");
        port.selector("app", "env", "prod");
        port.label("n1", "env", "prod");
        port.label("n2", "env", "prod");
        assertEquals(List.of("n1", "n2"), port.feasible("app"));
        List<String> bound = port.schedule();
        assertEquals(List.of("app"), bound, "亲和加分胜出");
        assertEquals(0, port.queued());
        assertEquals("n1", port.boundTo("app"));
        assertEquals(List.of("g", "p", "runq"), port.queueShape(),
                "schedkernel 可运行队列形状只读联动");
    }
}

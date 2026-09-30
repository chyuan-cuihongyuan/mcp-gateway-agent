package cn.chyuan.ai.domain.clusterkernel.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 集群容错内核测试（工单 1082-1089 ER1-ER8，dubbo 思想）。
 * 服务目录订阅推送/failover/failfast·failsafe/forking/broadcast/四负载均衡/引用计数/端口组合管线。
 */
class ClusterKernelTest {

    @Test
    void serviceDirectory() {
        Directories directory = new Directories();
        List<String> pushed = new ArrayList<>();
        directory.subscribe("svc", event -> pushed.add(event.type() + ":" + event.provider()));
        directory.register("svc", "p1");
        directory.register("svc", "p1");
        assertEquals(List.of("p1"), directory.providers("svc"), "重复注册幂等");
        assertEquals(List.of("JOIN:p1"), pushed, "注册订阅推送一次");
        directory.register("svc", "p2");
        directory.unregister("svc", "p1");
        assertEquals(List.of("p2"), directory.providers("svc"), "下线即时摘除");
        assertEquals(List.of("JOIN:p1", "JOIN:p2", "LEAVE:p1"), pushed);
        assertThrows(IllegalArgumentException.class,
                () -> directory.unregister("svc", "ghost"), "未注册下线拒绝");
        assertThrows(IllegalArgumentException.class, () -> directory.register(" ", "p"));
    }

    @Test
    void failoverRetry() {
        List<String> tried = new ArrayList<>();
        Failovers.Attempt attempt = Failovers.invoke(List.of("p1", "p2", "p3"), 2, provider -> {
            tried.add(provider);
            if (!provider.equals("p3")) {
                throw new IllegalStateException("fail");
            }
            return "R:" + provider;
        });
        assertEquals("p3", attempt.provider());
        assertEquals("R:p3", attempt.result());
        assertEquals(List.of("p1", "p2"), attempt.priorFailures());
        assertEquals(List.of("p1", "p2", "p3"), tried, "顺序推进不回退已失败者");

        List<String> calls = new ArrayList<>();
        Failovers.invoke(List.of("ok1", "ok2"), 3, provider -> {
            calls.add(provider);
            return "v";
        });
        assertEquals(List.of("ok1"), calls, "成功即返");

        try {
            Failovers.invoke(List.of("p1", "p2"), 5, provider -> {
                throw new IllegalStateException("x");
            });
            fail("耗尽应整体失败");
        } catch (Failovers.ClusterException exception) {
            assertEquals(List.of("p1", "p2"), exception.failedProviders(), "尝试数受候选钳制");
        }
        assertThrows(IllegalArgumentException.class, () -> Failovers.invoke(List.of(), 1, provider -> "x"));
        assertThrows(IllegalArgumentException.class,
                () -> Failovers.invoke(List.of("p1"), -1, provider -> "x"));
    }

    @Test
    void failfastAndFailsafe() {
        assertEquals("R", FailPolicies.failfast(List.of("p1"), provider -> "R").result());
        List<String> calls = new ArrayList<>();
        assertThrows(Failovers.ClusterException.class, () -> FailPolicies.failfast(List.of("p1", "p2"),
                provider -> {
                    calls.add(provider);
                    throw new IllegalStateException("f");
                }));
        assertEquals(List.of("p1"), calls, "failfast 一次失败即败不重试");

        FailPolicies.Outcome safe = FailPolicies.failsafe(List.of("p1"), "DEFAULT", provider -> {
            throw new IllegalStateException("f");
        });
        assertEquals("DEFAULT", safe.result(), "failsafe 吞异常返缺省");
        assertEquals(List.of("p1"), safe.failedProviders(), "失败留痕");
        assertEquals("R", FailPolicies.failsafe(List.of("p1"), "DEFAULT", provider -> "R").result());
        assertThrows(IllegalArgumentException.class,
                () -> FailPolicies.failfast(List.of(), provider -> "x"));
    }

    @Test
    void forkingParallel() {
        Forkings.Fork slow = new Forkings.Fork("slow", 10, true, "S");
        Forkings.Fork fast = new Forkings.Fork("fast", 1, true, "F");
        assertEquals("F", Forkings.invoke(List.of(slow, fast), 2), "最快成功者胜出");
        Forkings.Fork fastFail = new Forkings.Fork("fast-fail", 1, false, null);
        assertEquals("S", Forkings.invoke(List.of(fastFail, slow), 2), "快路失败取下一成功");
        assertThrows(Failovers.ClusterException.class, () -> Forkings.invoke(List.of(fastFail), 2),
                "全部失败整体失败");
        assertEquals("S", Forkings.invoke(List.of(slow, fast), 1), "并行度钳制只评估首分叉");
        assertThrows(IllegalArgumentException.class, () -> Forkings.invoke(List.of(fast), 0));
        assertThrows(IllegalArgumentException.class, () -> Forkings.invoke(List.of(), 1));
    }

    @Test
    void broadcastCalls() {
        List<String> calls = new ArrayList<>();
        List<String> results = Broadcasts.invoke(List.of("p1", "p2"), provider -> {
            calls.add(provider);
            return "R:" + provider;
        });
        assertEquals(List.of("R:p1", "R:p2"), results, "结果聚合返回");
        try {
            Broadcasts.invoke(List.of("p1", "p2"), provider -> {
                calls.add(provider);
                if (provider.equals("p2")) {
                    throw new IllegalStateException("f");
                }
                return "R:" + provider;
            });
            fail("任一失败应整体失败");
        } catch (Failovers.ClusterException exception) {
            assertEquals(List.of("p2"), exception.failedProviders());
        }
        assertEquals(4, calls.size(), "失败后仍全员逐个调用");
        assertThrows(IllegalArgumentException.class,
                () -> Broadcasts.invoke(List.of(), provider -> "x"), "无提供者拒绝");
    }

    @Test
    void loadBalanceStrategies() {
        Random random = new Random(42);
        List<LoadBalancers.Node> nodes =
                List.of(new LoadBalancers.Node("heavy", 9), new LoadBalancers.Node("light", 1));
        Map<String, Integer> counts = new HashMap<>();
        for (int index = 0; index < 1000; index++) {
            counts.merge(LoadBalancers.random(nodes, random), 1, Integer::sum);
        }
        assertTrue(counts.get("heavy") > counts.get("light") * 4, "随机按权重倾斜分布");

        LoadBalancers.RoundRobin roundRobin = new LoadBalancers.RoundRobin(nodes);
        for (int index = 0; index < 9; index++) {
            assertEquals("heavy", roundRobin.next());
        }
        assertEquals("light", roundRobin.next(), "权重展开轮转");
        assertEquals("heavy", roundRobin.next(), "循环回绕");

        LoadBalancers.LeastActive leastActive = new LoadBalancers.LeastActive();
        leastActive.begin("a");
        leastActive.begin("a");
        leastActive.begin("b");
        assertEquals("b", leastActive.select(List.of("a", "b")));
        leastActive.end("a");
        leastActive.end("a");
        assertEquals("a", leastActive.select(List.of("a", "b")), "活跃清零平局取首");
        assertThrows(IllegalArgumentException.class, () -> leastActive.end("c"));

        List<String> providers = List.of("p1", "p2", "p3");
        String first = LoadBalancers.consistentHash(providers, "user-1");
        assertEquals(first, LoadBalancers.consistentHash(providers, "user-1"), "同参数稳定命中");
        Map<String, String> before = new HashMap<>();
        for (int index = 0; index < 100; index++) {
            before.put("k" + index, LoadBalancers.consistentHash(providers, "k" + index));
        }
        List<String> scaled = List.of("p1", "p2", "p3", "p4");
        int moved = 0;
        for (String key : before.keySet()) {
            if (!before.get(key).equals(LoadBalancers.consistentHash(scaled, key))) {
                moved++;
            }
        }
        assertTrue(moved < 50, "扩容仅少数键迁移: moved=" + moved);
        assertThrows(IllegalArgumentException.class,
                () -> LoadBalancers.consistentHash(List.of(), "k"));
        assertThrows(IllegalArgumentException.class,
                () -> LoadBalancers.consistentHash(providers, " "));
        assertThrows(IllegalArgumentException.class,
                () -> new LoadBalancers.RoundRobin(List.of(new LoadBalancers.Node("x", 0))));
    }

    @Test
    void referenceCounting() {
        References references = new References();
        List<String> destroyed = new ArrayList<>();
        references.acquire("ref", () -> destroyed.add("ref"));
        references.acquire("ref");
        assertEquals(2, references.count("ref"));
        references.release("ref");
        assertFalse(references.destroyed("ref"), "未归零不销毁");
        references.release("ref");
        assertTrue(references.destroyed("ref"), "归零触发销毁回调");
        assertEquals(List.of("ref"), destroyed);
        assertThrows(IllegalStateException.class, () -> references.acquire("ref"), "销毁后 acquire 拒绝");
        assertThrows(IllegalStateException.class, () -> references.release("ref"));
        assertThrows(IllegalArgumentException.class, () -> references.release("ghost"));
    }

    @Test
    void clusterPortPipeline() {
        ClusterPort port = ClusterPort.inMemory(42);
        port.register("order", "p1", 1);
        port.register("order", "p2", 1);
        port.register("order", "p3", 1);
        assertEquals(List.of("JOIN:p1", "JOIN:p2", "JOIN:p3"), port.directoryEvents("order"));

        port.behave("p1", false, null);
        assertEquals("p2>ok:p2", port.invoke("order", "FAILOVER", 2));
        assertEquals(1, port.failures("p1").size(), "失败计数留痕");
        assertThrows(Failovers.ClusterException.class, () -> port.invoke("order", "FAILFAST", 1));

        port.behave("p2", true, "SAFE");
        assertEquals("SAFE", port.invoke("order", "FAILSAFE", 0), "failsafe 逐候选取首个成功");

        port.behave("p2", true, "ok:p2");
        port.behave("p3", true, "ok:p3");
        assertEquals("ok:p2", port.invoke("order", "FORKING", 3), "并行取首成功");

        port.behave("p1", true, "ok:p1");
        assertEquals(List.of("ok:p1", "ok:p2", "ok:p3"), port.broadcast("order"));

        assertEquals("p1", port.select("order", "ROUND_ROBIN", null));
        String hashed = port.select("order", "CONSISTENT_HASH", "tenant-a");
        assertTrue(List.of("p1", "p2", "p3").contains(hashed));
        assertEquals(hashed, port.select("order", "CONSISTENT_HASH", "tenant-a"));
        assertNotNull(port.select("order", "RANDOM", null));
        port.active("p1", 1);
        assertEquals("p2", port.select("order", "LEAST_ACTIVE", null));
        port.active("p1", -1);

        port.acquire("cluster-ref");
        port.acquire("cluster-ref");
        assertEquals(2, port.refCount("cluster-ref"));
        port.release("cluster-ref");
        port.release("cluster-ref");
        assertTrue(port.refDestroyed("cluster-ref"), "归零销毁");

        port.unregister("order", "p3");
        assertEquals(List.of("p1", "p2"), port.providers("order"));
        assertThrows(IllegalArgumentException.class, () -> port.invoke("order", "NOPE", 1));
        assertThrows(IllegalArgumentException.class,
                () -> ClusterPort.inMemory(1).invoke("ghost", "FAILOVER", 1), "空目录调用拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.register("order", "p9", 0));
        assertEquals(List.of("service", "method", "fullName", "methodType"),
                port.contractShape(), "rpckernel 契约形状只读联动");
    }
}

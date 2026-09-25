package cn.chyuan.ai.domain.controllerkernel.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 控制循环内核测试（工单 0793-0800 CP1-CP8，kubernetes 控制器思想）。
 * 资源模型/informer 去重/限速退避队列/reconcile 幂等/owner 级联/finalizer 两阶段/conditions 聚合/端口编排。
 */
class ControllerKernelTest {

    private static Resource res(String name, String version) {
        return new Resource("ConfigMap", "default", name, null, Map.of("version", version));
    }

    @Test
    void resourceModel() {
        Resource r = res("app", "v1");
        assertEquals("ConfigMap/default/app", r.key());
        assertEquals(1, r.generation());
        r.updateSpec(Map.of("version", "v1"));
        assertEquals(1, r.generation(), "spec 未变不递增");
        r.updateSpec(Map.of("version", "v2"));
        assertEquals(2, r.generation(), "spec 变更递增");
        Resource copy = r.copy();
        copy.status(Map.of("phase", "Ready"));
        assertEquals(Map.of(), r.status(), "副本 status 独立");
        assertTrue(r.specEquals(copy));
        assertThrows(IllegalArgumentException.class, () -> new Resource("", "d", "n", null, Map.of()));
    }

    @Test
    void informerDedupAndOrder() {
        Informer informer = new Informer();
        List<String> seen = new ArrayList<>();
        informer.addHandler(e -> seen.add(e.type() + ":" + e.key()));
        informer.emit(new Informer.Event(Informer.Type.UPDATE, res("a", "v1")));
        informer.emit(new Informer.Event(Informer.Type.ADD, res("b", "v1")));
        informer.emit(new Informer.Event(Informer.Type.UPDATE, res("a", "v2")));
        assertEquals(2, informer.pendingCount(), "同键合并为最近");
        List<Informer.Event> drained = informer.drain();
        assertEquals("a", drained.get(0).resource().name(), "保首现位置");
        assertEquals("v2", drained.get(0).resource().spec().get("version"), "内容为最近");
        assertEquals(0, informer.pendingCount());
        assertEquals(List.of("UPDATE:ConfigMap/default/a", "ADD:ConfigMap/default/b"), seen);
    }

    @Test
    void workQueueTokensAndDedup() {
        WorkQueue q = new WorkQueue(1.0, 2, 2, 8);
        q.add("k1");
        q.add("k1");
        q.add("k2");
        q.advance();
        assertEquals("k1", q.pop());
        assertEquals("k2", q.pop());
        assertNull(q.pop(), "令牌耗尽");
        q.add("k3");
        q.advance();
        assertEquals("k3", q.pop(), "令牌按步补充");
    }

    @Test
    void workQueueBackoffAndReset() {
        WorkQueue q = new WorkQueue(1.0, 4, 2, 4);
        q.add("k");
        q.advance();
        assertEquals("k", q.pop());
        q.fail("k");
        q.advance();
        assertNull(q.pop(), "退避未到期不弹");
        q.advance();
        assertEquals("k", q.pop(), "退避 2 步到期");
        q.fail("k");
        for (int i = 0; i < 4; i++) {
            q.advance();
            if (i < 3) {
                assertNull(q.pop(), "二轮退避 4 步封顶");
            }
        }
        assertEquals("k", q.pop());
        q.done("k");
        assertEquals(0, q.failuresOf("k"), "成功重置");
        assertTrue(q.eligible("k"));
        assertFalse(q.contains("k"), "弹出后不在队列");
    }

    @Test
    void reconcilerPlans() {
        Reconciler r = new Reconciler();
        assertEquals(Reconciler.Action.CREATE, r.plan(res("x", "v1"), null).action());
        assertEquals(Reconciler.Action.NONE, r.plan(res("x", "v1"), res("x", "v1")).action());
        assertEquals(Reconciler.Action.UPDATE, r.plan(res("x", "v2"), res("x", "v1")).action());
        assertEquals(Reconciler.Action.DELETE, r.plan(null, res("x", "v1")).action());
        assertEquals(Reconciler.Action.NONE, r.plan(null, null).action());
        assertTrue(Reconciler.idempotent(r, res("x", "v2"), res("x", "v1"), 5), "重复协调动作一致");
        assertThrows(IllegalArgumentException.class,
                () -> r.plan(res("x", "v1"), res("y", "v1")), "键不一致拒绝");
    }

    @Test
    void reconcilerApplyWriteback() {
        Reconciler r = new Reconciler();
        Map<String, Resource> store = new java.util.LinkedHashMap<>();
        Resource created = r.apply(r.plan(res("app", "v1"), null), store);
        assertEquals("Ready", created.status().get("phase"));
        assertSame(created, store.get("ConfigMap/default/app"));
        Resource want = res("app", "v2");
        Resource updated = r.apply(r.plan(want, store.get(want.key())), store);
        assertEquals("v2", updated.spec().get("version"), "spec 以期望覆盖");
        assertEquals(want.generation(), updated.status().get("observedGeneration"));
    }

    @Test
    void ownerCascadeAndOrphanAndCycle() {
        OwnerReferences owners = new OwnerReferences();
        owners.register("a", "b");
        owners.register("b", "c");
        assertEquals(List.of("c", "b"), owners.cascadeDelete("a"), "深者先删");
        OwnerReferences cycle = new OwnerReferences();
        cycle.register("x", "y");
        assertThrows(IllegalArgumentException.class, () -> cycle.register("y", "x"), "环引用拒绝");
        owners.register("p", "kid");
        List<String> orphans = owners.orphans(Set.of("gone"));
        assertEquals(List.of("kid"), orphans, "owner 不存活即孤儿回收");
        assertNull(owners.ownerOf("kid"));
    }

    @Test
    void finalizersTwoPhase() {
        Finalizers f = new Finalizers();
        f.add("res", "backup");
        f.registerCleaner("backup", key -> f.clear(key, "backup"));
        assertTrue(f.delete("res"), "清理器摘除后真删");
        f.add("res2", "hold");
        assertFalse(f.delete("res2"), "finalizer 未清不真删");
        assertTrue(f.stuck("res2"), "卡死检测");
        assertTrue(f.clear("res2", "hold"));
        assertTrue(f.delete("res2"));
        assertFalse(f.stuck("res2"));
    }

    @Test
    void conditionsAggregateAndMonotonic() {
        Conditions c = new Conditions();
        c.set("Synced", Conditions.TRUE, "ok");
        long at = c.get("Synced").lastTransition();
        c.set("Synced", Conditions.TRUE, "still-ok");
        assertEquals(at, c.get("Synced").lastTransition(), "status 未变时间不推进");
        c.set("Synced", Conditions.FALSE, "drift");
        assertTrue(c.get("Synced").lastTransition() > at, "status 变化时间单调推进");
        assertTrue(c.monotonic("Synced", at));
        assertFalse(c.ready(List.of("Synced", "Quorum")), "缺条件不 ready");
        c.set("Quorum", Conditions.TRUE, "majority");
        c.set("Synced", Conditions.TRUE, "fixed");
        assertTrue(c.ready(List.of("Synced", "Quorum")));
        c.set("Quorum", Conditions.FALSE, "lost");
        assertFalse(c.ready(List.of("Synced", "Quorum")));
    }

    @Test
    void portCreateThenSync() {
        ControllerPort port = ControllerPort.inMemory();
        port.declare(res("app", "v1"));
        ControllerPort.StepReport step = port.step();
        assertTrue(step.factConfirmed());
        assertEquals(1, step.events());
        assertEquals(List.of("CREATE ConfigMap/default/app"), step.actions());
        assertNotNull(port.live("ConfigMap/default/app"));
        ControllerPort.StepReport steady = port.step();
        assertTrue(steady.actions().isEmpty(), "收敛后无动作");
        assertEquals(0, steady.events());
    }

    @Test
    void portDriftRepair() {
        ControllerPort port = ControllerPort.inMemory();
        port.declare(res("app", "v1"));
        port.step();
        Resource drifted = res("app", "v0");
        port.adopt(drifted);
        ControllerPort.StepReport step = port.step();
        assertEquals(List.of("UPDATE ConfigMap/default/app"), step.actions(), "漂移修复");
        assertEquals("v1", port.live("ConfigMap/default/app").spec().get("version"));
    }

    @Test
    void portDeleteWithFinalizer() {
        ControllerPort port = ControllerPort.inMemory();
        port.declare(res("app", "v1"));
        port.step();
        String key = "ConfigMap/default/app";
        port.addFinalizer(key, "drain");
        port.registerCleaner("drain", k -> {
        });
        port.withdraw(key);
        ControllerPort.StepReport finalizing = port.step();
        assertEquals(List.of("FINALIZING " + key), finalizing.actions(), "finalizer 挡删除");
        assertNotNull(port.live(key), "资源仍在");
        assertTrue(port.stuck(key));
        assertTrue(port.clearFinalizer(key, "drain"));
        ControllerPort.StepReport deleted = port.step();
        assertEquals(List.of("DELETE " + key), deleted.actions());
        assertNull(port.live(key));
    }

    @Test
    void portFactSourceGates() {
        ControllerPort port = ControllerPort.inMemory();
        port.withFactSource(() -> false);
        port.declare(res("app", "v1"));
        ControllerPort.StepReport blocked = port.step();
        assertFalse(blocked.factConfirmed(), "多数派未确认");
        assertTrue(blocked.actions().isEmpty(), "事实源未确认不动作");
        port.withFactSource(() -> true);
        ControllerPort.StepReport unblocked = port.step();
        assertEquals(List.of("CREATE ConfigMap/default/app"), unblocked.actions(), "确认后恢复收敛");
    }
}

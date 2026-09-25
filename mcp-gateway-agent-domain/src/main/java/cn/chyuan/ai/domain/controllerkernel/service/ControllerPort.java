package cn.chyuan.ai.domain.controllerkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * 控制器端口（工单 0800 CP8，kubernetes 控制器思想）。
 * declare·withdraw·adopt·step 一次 level-trigger 控制循环统一编排/与 raftkernel 只读联动
 * （多数派提交状态作集群事实源形态，BooleanSupplier 泛型入参不 import）/controller-kernel.enabled 默认关（开启才改变行为）。
 */
public interface ControllerPort {

    /** 单步循环报告：派发事件数/执行动作清单/事实源是否确认 */
    record StepReport(int events, List<String> actions, boolean factConfirmed) {
    }

    /** 期望态登记（ADD/UPDATE 事件） */
    void declare(Resource desired);

    /** 期望态撤销（DELETE 事件） */
    void withdraw(String key);

    /** 集群实际态上报（informer watch 推送形态） */
    void adopt(Resource live);

    /** raftkernel 只读联动：多数派提交确认器（默认恒真） */
    void withFactSource(BooleanSupplier majorityCommitted);

    /** finalizer 登记/清理器注册/显式摘除 */
    void addFinalizer(String key, String name);

    void registerCleaner(String name, java.util.function.Consumer<String> cleaner);

    boolean clearFinalizer(String key, String name);

    boolean stuck(String key);

    Resource live(String key);

    /** 执行一次控制循环：事件→工作队列（限速）→reconcile→status 回写 */
    StepReport step();

    static ControllerPort inMemory() {
        return new InMemoryController();
    }
}

final class InMemoryController implements ControllerPort {

    private final Informer informer = new Informer();
    private final WorkQueue queue = new WorkQueue(1.0, 8, 2, 8);
    private final Reconciler reconciler = new Reconciler();
    private final Conditions conditions = new Conditions();
    private final Finalizers finalizers = new Finalizers();
    private final Map<String, Resource> desired = new LinkedHashMap<>();
    private final Map<String, Resource> live = new LinkedHashMap<>();
    private BooleanSupplier factSource = () -> true;

    InMemoryController() {
        informer.addHandler(event -> queue.add(event.key()));
    }

    @Override
    public void declare(Resource r) {
        boolean isNew = !desired.containsKey(r.key());
        desired.put(r.key(), r);
        informer.emit(new Informer.Event(isNew ? Informer.Type.ADD : Informer.Type.UPDATE, r));
    }

    @Override
    public void withdraw(String key) {
        Resource last = desired.remove(key);
        if (last != null) {
            informer.emit(new Informer.Event(Informer.Type.DELETE, last));
        }
    }

    @Override
    public void adopt(Resource liveResource) {
        live.put(liveResource.key(), liveResource);
        informer.emit(new Informer.Event(Informer.Type.UPDATE, liveResource));
    }

    @Override
    public void withFactSource(BooleanSupplier majorityCommitted) {
        this.factSource = majorityCommitted;
    }

    @Override
    public void addFinalizer(String key, String name) {
        finalizers.add(key, name);
    }

    @Override
    public void registerCleaner(String name, java.util.function.Consumer<String> cleaner) {
        finalizers.registerCleaner(name, cleaner);
    }

    @Override
    public boolean clearFinalizer(String key, String name) {
        return finalizers.clear(key, name);
    }

    @Override
    public boolean stuck(String key) {
        return finalizers.stuck(key);
    }

    @Override
    public Resource live(String key) {
        return live.get(key);
    }

    @Override
    public StepReport step() {
        boolean fact = factSource.getAsBoolean();
        queue.advance();
        List<Informer.Event> events = informer.drain();
        List<String> actions = new ArrayList<>();
        if (!fact) {
            return new StepReport(events.size(), actions, false);
        }
        String key;
        List<String> retriable = new ArrayList<>();
        while ((key = queue.pop()) != null) {
            reconcileKey(key, actions, retriable);
        }
        retriable.forEach(queue::add);
        return new StepReport(events.size(), actions, true);
    }

    private void reconcileKey(String key, List<String> actions, List<String> retriable) {
        Resource want = desired.get(key);
        Resource have = live.get(key);
        Reconciler.Plan plan = reconciler.plan(want, have);
        switch (plan.action()) {
            case CREATE -> {
                reconciler.apply(plan, live);
                conditions.set("Synced", Conditions.TRUE, "created");
                actions.add("CREATE " + key);
            }
            case UPDATE -> {
                reconciler.apply(plan, live);
                conditions.set("Synced", Conditions.TRUE, "updated");
                actions.add("UPDATE " + key);
            }
            case DELETE -> {
                boolean removed = finalizers.delete(key);
                if (removed) {
                    live.remove(key);
                    actions.add("DELETE " + key);
                } else {
                    conditions.set("Terminating", Conditions.FALSE, "finalizers pending");
                    actions.add("FINALIZING " + key);
                    retriable.add(key);
                }
            }
            default -> conditions.set("Synced", Conditions.TRUE, "in-sync");
        }
    }
}

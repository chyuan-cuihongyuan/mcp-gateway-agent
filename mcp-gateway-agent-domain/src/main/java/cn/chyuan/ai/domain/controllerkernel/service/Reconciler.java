package cn.chyuan.ai.domain.controllerkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * reconcile 协调（工单 0796 CP4，kubernetes 控制器思想）。
 * 幂等 diff 出 CREATE/UPDATE/DELETE/NONE 动作集/重复协调收敛一致/动作执行回写 status。
 */
public final class Reconciler {

    public enum Action { NONE, CREATE, UPDATE, DELETE }

    public record Plan(String key, Resource desired, Resource live, Action action) {
    }

    /** 期望与实际 diff：单资源单一动作 */
    public Plan plan(Resource desired, Resource live) {
        if (desired == null && live == null) {
            return new Plan(null, null, null, Action.NONE);
        }
        if (desired == null) {
            return new Plan(live.key(), null, live, Action.DELETE);
        }
        if (live == null) {
            return new Plan(desired.key(), desired, null, Action.CREATE);
        }
        if (!desired.key().equals(live.key())) {
            throw new IllegalArgumentException("键不一致: " + desired.key() + " vs " + live.key());
        }
        return new Plan(desired.key(), desired, live, desired.specEquals(live) ? Action.NONE : Action.UPDATE);
    }

    /** 执行动作：CREATE/UPDATE 以期望 spec 覆盖实际并回写 status 同步标记；DELETE 由 finalizer 层处理 */
    public Resource apply(Plan plan, Map<String, Resource> liveStore) {
        switch (plan.action()) {
            case CREATE -> {
                Resource created = plan.desired().copy();
                created.status(Map.of("phase", "Ready", "observedGeneration", created.generation()));
                liveStore.put(created.key(), created);
                return created;
            }
            case UPDATE -> {
                Resource updated = plan.live();
                updated.updateSpec(plan.desired().spec());
                updated.status(Map.of("phase", "Ready", "observedGeneration", plan.desired().generation()));
                return updated;
            }
            default -> {
                return plan.live();
            }
        }
    }

    /** 幂等自检：同一对输入重复 plan 结论一致 */
    public static boolean idempotent(Reconciler r, Resource desired, Resource live, int times) {
        Action first = r.plan(desired, live).action();
        for (int i = 1; i < times; i++) {
            if (r.plan(desired, live).action() != first) {
                return false;
            }
        }
        return true;
    }
}

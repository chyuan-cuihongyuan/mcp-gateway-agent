package cn.chyuan.ai.domain.actorkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 空闲失活（工单 1009 EI5，dapr 空闲失活思想）。
 * idle 计数步进/超阈值失活清状态/失活后再激活重建。
 */
public final class IdleReaper {

    private final Map<String, Long> idleTicks = new LinkedHashMap<>();

    /** 活动：重置 idle 计数 */
    public synchronized void touch(String actorKey) {
        if (actorKey == null || actorKey.isEmpty()) {
            throw new IllegalArgumentException("actor 键为空");
        }
        idleTicks.put(actorKey, 0L);
    }

    /** 注销：失活后不再参与计数 */
    public synchronized void remove(String actorKey) {
        idleTicks.remove(actorKey);
    }

    /** 步进：全部活跃 actor idle+1 */
    public synchronized void tick() {
        idleTicks.replaceAll((k, v) -> v + 1);
    }

    public synchronized long idle(String actorKey) {
        return idleTicks.getOrDefault(actorKey, 0L);
    }

    /** 收割：idle 严格超阈值的 actor 失活（返回名单并移除计数） */
    public synchronized List<String> reap(long threshold) {
        List<String> evicted = new ArrayList<>();
        idleTicks.forEach((k, v) -> {
            if (v > threshold) {
                evicted.add(k);
            }
        });
        evicted.forEach(idleTicks::remove);
        return evicted;
    }
}

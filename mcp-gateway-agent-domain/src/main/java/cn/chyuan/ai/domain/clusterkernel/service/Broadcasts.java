package cn.chyuan.ai.domain.clusterkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * broadcast 广播调用（工单 1086 ER5，dubbo 思想）。
 * 全员逐个调用/任一失败整体失败（已成功结果随异常携带）/无提供者拒绝/成功时结果聚合返回。
 */
public final class Broadcasts {

    private Broadcasts() {
    }

    /** 全员调用：全部成功返回聚合结果；任一失败抛 ClusterException（含已完成结果与失败者） */
    public static List<String> invoke(List<String> providers, Failovers.Call call) {
        if (providers == null || providers.isEmpty()) {
            throw new IllegalArgumentException("无提供者拒绝广播");
        }
        List<String> results = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String provider : providers) {
            try {
                results.add(call.call(provider));
            } catch (RuntimeException exception) {
                failed.add(provider);
            }
        }
        if (!failed.isEmpty()) {
            Failovers.ClusterException exception =
                    new Failovers.ClusterException("broadcast 任一失败整体失败", failed);
            exception.addSuppressed(new Throwable("completed=" + results));
            throw exception;
        }
        return results;
    }
}

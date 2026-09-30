package cn.chyuan.ai.domain.clusterkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * failfast·failsafe 容错策略（工单 1084 ER3，dubbo 思想）。
 * failfast 一次失败即败不重试/failsafe 吞异常返缺省/策略差异按调用指定/失败提供者留痕。
 */
public final class FailPolicies {

    /** 策略结果：返回值 + 失败留痕 */
    public record Outcome(String result, List<String> failedProviders) {
    }

    private FailPolicies() {
    }

    /** failfast：仅尝试首个提供者，失败立即整体失败 */
    public static Outcome failfast(List<String> providers, Failovers.Call call) {
        if (providers == null || providers.isEmpty()) {
            throw new IllegalArgumentException("空目录调用拒绝");
        }
        String provider = providers.get(0);
        try {
            return new Outcome(call.call(provider), List.of());
        } catch (RuntimeException exception) {
            throw new Failovers.ClusterException("failfast 即败", List.of(provider));
        }
    }

    /** failsafe：吞掉异常返回缺省值，失败留痕 */
    public static Outcome failsafe(List<String> providers, String defaultValue, Failovers.Call call) {
        if (providers == null || providers.isEmpty()) {
            throw new IllegalArgumentException("空目录调用拒绝");
        }
        List<String> failed = new ArrayList<>();
        for (String provider : providers) {
            try {
                return new Outcome(call.call(provider), List.copyOf(failed));
            } catch (RuntimeException exception) {
                failed.add(provider);
            }
        }
        return new Outcome(defaultValue, List.copyOf(failed));
    }
}

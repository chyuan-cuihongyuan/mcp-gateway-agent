package cn.chyuan.ai.domain.envoykernel.service;

import java.util.HashMap;
import java.util.Map;

/**
 * 主动健康检查（工单 0937 EA2，envoy 思想）。
 * 健康/不健康阈值翻转/探活请求序/未达阈值维持原态。
 */
public final class HealthChecker {

    private final int healthyThreshold;
    private final int unhealthyThreshold;
    private final Map<EnvoyCluster.Endpoint, Integer> okStreak = new HashMap<>();
    private final Map<EnvoyCluster.Endpoint, Integer> failStreak = new HashMap<>();
    private int probeSeq = 0;

    public HealthChecker(int healthyThreshold, int unhealthyThreshold) {
        if (healthyThreshold <= 0 || unhealthyThreshold <= 0) {
            throw new IllegalArgumentException("健康阈值非法: " + healthyThreshold + "/" + unhealthyThreshold);
        }
        this.healthyThreshold = healthyThreshold;
        this.unhealthyThreshold = unhealthyThreshold;
    }

    /** 探活一步：返回探活序号；连续成功/失败达阈值才翻转，未达阈值维持原态 */
    public int probe(EnvoyCluster.Endpoint endpoint, boolean ok) {
        probeSeq++;
        if (ok) {
            int streak = okStreak.merge(endpoint, 1, Integer::sum);
            failStreak.put(endpoint, 0);
            if (!endpoint.healthy && streak >= healthyThreshold) {
                endpoint.healthy = true;
            }
        } else {
            int streak = failStreak.merge(endpoint, 1, Integer::sum);
            okStreak.put(endpoint, 0);
            if (endpoint.healthy && streak >= unhealthyThreshold) {
                endpoint.healthy = false;
            }
        }
        return probeSeq;
    }

    public int probes() {
        return probeSeq;
    }
}

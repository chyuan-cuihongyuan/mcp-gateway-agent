package cn.chyuan.ai.domain.envoykernel.service;

import java.util.HashMap;
import java.util.Map;

/**
 * 异常点剔除（工单 0938 EA3，envoy 思想）。
 * 连续 5xx 弹出/冷却期后试探回归/成功复位计数。
 */
public final class OutlierDetector {

    private final int consecutive5xx;
    private final int cooldownProbes;
    private final Map<EnvoyCluster.Endpoint, Integer> errorStreak = new HashMap<>();
    private final Map<EnvoyCluster.Endpoint, Integer> cooldownLeft = new HashMap<>();

    public OutlierDetector(int consecutive5xx, int cooldownProbes) {
        if (consecutive5xx <= 0 || cooldownProbes <= 0) {
            throw new IllegalArgumentException("剔除参数非法: " + consecutive5xx + "/" + cooldownProbes);
        }
        this.consecutive5xx = consecutive5xx;
        this.cooldownProbes = cooldownProbes;
    }

    /** 上报一次结果：连续 5xx 达阈值弹出；2xx 复位计数，冷却期满且试探成功则回归 */
    public void report(EnvoyCluster.Endpoint endpoint, int status) {
        if (status >= 500) {
            int streak = errorStreak.merge(endpoint, 1, Integer::sum);
            if (streak >= consecutive5xx && !endpoint.ejected) {
                endpoint.ejected = true;
                cooldownLeft.put(endpoint, cooldownProbes);
            } else if (endpoint.ejected && cooldown(endpoint) <= 0) {
                // 试探期再失败：重新冷却
                cooldownLeft.put(endpoint, cooldownProbes);
            }
        } else {
            errorStreak.put(endpoint, 0);
            if (endpoint.ejected && cooldown(endpoint) <= 0) {
                // 试探成功回归
                endpoint.ejected = false;
            }
        }
    }

    /** 冷却步进：由探活驱动，被剔除端点每探活一次冷却减一 */
    public void tick(EnvoyCluster.Endpoint endpoint) {
        if (endpoint.ejected && cooldown(endpoint) > 0) {
            cooldownLeft.merge(endpoint, -1, Integer::sum);
        }
    }

    /** 剔除状态读数：ejected/cooling/probing */
    public String state(EnvoyCluster.Endpoint endpoint) {
        if (!endpoint.ejected) {
            return "active";
        }
        return cooldown(endpoint) > 0 ? "cooling" : "probing";
    }

    private int cooldown(EnvoyCluster.Endpoint endpoint) {
        return cooldownLeft.getOrDefault(endpoint, 0);
    }
}

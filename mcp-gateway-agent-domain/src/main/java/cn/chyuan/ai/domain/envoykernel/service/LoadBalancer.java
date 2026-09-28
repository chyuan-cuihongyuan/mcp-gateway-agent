package cn.chyuan.ai.domain.envoykernel.service;

import java.util.List;
import java.util.Set;

/**
 * 加权最小请求负载均衡（工单 0942 EA7，envoy 思想）。
 * 健康且未剔除端点中选 inFlight 最小（并列取权重大者，再并列取注册序）/全部不可用拒绝。
 */
public final class LoadBalancer {

    private final EnvoyCluster cluster;

    public LoadBalancer(EnvoyCluster cluster) {
        this.cluster = cluster;
    }

    /** 选择端点（无排除集） */
    public EnvoyCluster.Endpoint pick(String clusterName) {
        return pick(clusterName, Set.of());
    }

    /** 选择端点：可排除已试端点（重试换点）；无可用端点拒绝 */
    public EnvoyCluster.Endpoint pick(String clusterName, Set<String> excluded) {
        List<EnvoyCluster.Endpoint> endpoints = cluster.mutable(clusterName);
        EnvoyCluster.Endpoint best = null;
        int bestIndex = -1;
        for (int i = 0; i < endpoints.size(); i++) {
            EnvoyCluster.Endpoint e = endpoints.get(i);
            if (excluded.contains(e.address) || !e.healthy || e.ejected) {
                continue;
            }
            if (best == null || better(e, best, i, bestIndex)) {
                best = e;
                bestIndex = i;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("无可用端点: " + clusterName);
        }
        return best;
    }

    private boolean better(EnvoyCluster.Endpoint candidate, EnvoyCluster.Endpoint best, int candIndex, int bestIndex) {
        if (candidate.inFlight != best.inFlight) {
            return candidate.inFlight < best.inFlight;
        }
        if (candidate.weight != best.weight) {
            return candidate.weight > best.weight;
        }
        return candIndex < bestIndex;
    }
}

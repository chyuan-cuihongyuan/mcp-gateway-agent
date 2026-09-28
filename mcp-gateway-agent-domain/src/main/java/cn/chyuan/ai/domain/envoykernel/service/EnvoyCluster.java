package cn.chyuan.ai.domain.envoykernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 集群与端点表（工单 0936 EA1，envoy 思想）。
 * cluster·endpoint 注册/地址权重/重复拒绝/空集群拒绝。
 */
public final class EnvoyCluster {

    /** 端点：地址 + 权重 + 健康态 + 剔除标记 + 活跃请求数 */
    public static final class Endpoint {
        public final String address;
        public final int weight;
        public boolean healthy = true;
        public boolean ejected = false;
        public int inFlight = 0;

        Endpoint(String address, int weight) {
            this.address = address;
            this.weight = weight;
        }
    }

    private final Map<String, List<Endpoint>> clusters = new LinkedHashMap<>();

    /** 注册集群：重复拒绝 */
    public void addCluster(String name) {
        if (clusters.containsKey(name)) {
            throw new IllegalArgumentException("重复集群: " + name);
        }
        clusters.put(name, new ArrayList<>());
    }

    /** 注册端点：未知集群拒绝/权重非法拒绝/重复地址拒绝 */
    public void addEndpoint(String cluster, String address, int weight) {
        List<Endpoint> list = clusters.get(cluster);
        if (list == null) {
            throw new IllegalArgumentException("未知集群: " + cluster);
        }
        if (weight <= 0) {
            throw new IllegalArgumentException("权重非法: " + weight);
        }
        if (list.stream().anyMatch(e -> e.address.equals(address))) {
            throw new IllegalArgumentException("重复端点: " + address);
        }
        list.add(new Endpoint(address, weight));
    }

    /** 端点表只读视图：未知或空集群拒绝 */
    public List<Endpoint> endpoints(String cluster) {
        List<Endpoint> list = clusters.get(cluster);
        if (list == null || list.isEmpty()) {
            throw new IllegalArgumentException("空集群或未知集群: " + cluster);
        }
        return List.copyOf(list);
    }

    boolean exists(String cluster) {
        return clusters.containsKey(cluster);
    }

    /** 可能非空的端点列表（发现面使用，不拒绝空集群） */
    List<Endpoint> peek(String cluster) {
        return clusters.getOrDefault(cluster, List.of());
    }

    List<Endpoint> mutable(String cluster) {
        List<Endpoint> list = clusters.get(cluster);
        if (list == null || list.isEmpty()) {
            throw new IllegalArgumentException("空集群或未知集群: " + cluster);
        }
        return list;
    }

    /** 按地址取活动端点（内核内部使用），未注册返回 null */
    Endpoint live(String cluster, String address) {
        List<Endpoint> list = clusters.get(cluster);
        if (list == null) {
            return null;
        }
        return list.stream().filter(e -> e.address.equals(address)).findFirst().orElse(null);
    }
}

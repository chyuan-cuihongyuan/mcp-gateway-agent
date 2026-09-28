package cn.chyuan.ai.domain.envoykernel.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 代理自愈端口（工单 0943 EA8，envoy 思想）。
 * discover·route·exchange 入口统一编排/与 controllerkernel 调节数据作池状态形态只读联动（泛型 Map 不 import）/
 * envoy-kernel.enabled 默认关（开启才改变行为）。
 */
public interface EnvoyPort {

    /** xDS 简化面：发现集群端点（权重 1，重复地址跳过，幂等） */
    void discover(String cluster, List<String> addresses);

    /** 路由：加权最小请求选点 */
    EnvoyCluster.Endpoint route(String cluster);

    /** 上游调用形态：端点 + 请求 → 状态码 */
    interface Upstream {
        int status(String endpoint, String request);
    }

    /** 组合管线：选点→熔断→过滤器链→上游调用→异常点/健康上报→预算内重试退避 */
    String exchange(String cluster, String request, Upstream upstream);

    /** controllerkernel 调节数据形态：池观测状态（healthy/unhealthy/ejected/inFlight），控制器侧自行对照 desired */
    Map<String, Integer> reconcileData(String cluster);

    static EnvoyPort inMemory() {
        return new InMemoryEnvoy();
    }
}

final class InMemoryEnvoy implements EnvoyPort {

    private final EnvoyCluster cluster = new EnvoyCluster();
    private final HealthChecker health = new HealthChecker(2, 2);
    private final OutlierDetector outlier = new OutlierDetector(2, 1);
    private final CircuitBreaker breaker = new CircuitBreaker(8, 4);
    private final RetryPolicy retry = new RetryPolicy(2, 100, 5L);
    private final FilterChain filters = new FilterChain();
    private final LoadBalancer balancer = new LoadBalancer(cluster);

    @Override
    public void discover(String clusterName, List<String> addresses) {
        if (!cluster.exists(clusterName)) {
            cluster.addCluster(clusterName);
        }
        for (String address : addresses) {
            boolean present = cluster.peek(clusterName).stream().anyMatch(e -> e.address.equals(address));
            if (!present) {
                cluster.addEndpoint(clusterName, address, 1);
            }
        }
    }

    @Override
    public EnvoyCluster.Endpoint route(String clusterName) {
        return balancer.pick(clusterName);
    }

    @Override
    public String exchange(String clusterName, String request, Upstream upstream) {
        retry.onRequest();
        int attempt = 0;
        java.util.Set<String> tried = new java.util.HashSet<>();
        String last = null;
        while (true) {
            attempt++;
            EnvoyCluster.Endpoint endpoint;
            try {
                endpoint = balancer.pick(clusterName, tried);
            } catch (IllegalArgumentException exhausted) {
                endpoint = balancer.pick(clusterName);
                tried.clear();
            }
            CircuitBreaker.Slot slot = breaker.acquire();
            if (slot == CircuitBreaker.Slot.REJECTED) {
                throw new IllegalStateException("熔断拒绝: " + clusterName);
            }
            endpoint.inFlight++;
            FilterChain.Context ctx = filters.execute(request);
            int status = ctx.shortCircuited() ? 200 : upstream.status(endpoint.address, ctx.request);
            endpoint.inFlight--;
            breaker.release();
            tried.add(endpoint.address);
            outlier.report(endpoint, status);
            health.probe(endpoint, status < 500);
            last = endpoint.address + " " + status + " attempts=" + attempt;
            if (status < 500) {
                retry.onSuccess();
                return last;
            }
            if (!retry.allowRetry()) {
                return last;
            }
            retry.onRetry();
        }
    }

    @Override
    public Map<String, Integer> reconcileData(String clusterName) {
        Map<String, Integer> data = new LinkedHashMap<>();
        int healthy = 0;
        int unhealthy = 0;
        int ejected = 0;
        int inFlight = 0;
        for (EnvoyCluster.Endpoint e : cluster.endpoints(clusterName)) {
            if (e.healthy) {
                healthy++;
            } else {
                unhealthy++;
            }
            if (e.ejected) {
                ejected++;
            }
            inFlight += e.inFlight;
        }
        data.put("healthy", healthy);
        data.put("unhealthy", unhealthy);
        data.put("ejected", ejected);
        data.put("inFlight", inFlight);
        return data;
    }

    FilterChain filters() {
        return filters;
    }

    HealthChecker health() {
        return health;
    }

    OutlierDetector outlier() {
        return outlier;
    }

    EnvoyCluster cluster() {
        return cluster;
    }
}

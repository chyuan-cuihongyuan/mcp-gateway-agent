package cn.chyuan.ai.domain.clusterkernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 集群容错编排实现（工单 1089 ER8，dubbo 思想）。
 * 目录订阅事件留痕；行为注入（behave）驱动五容错策略；负载均衡四策略；
 * 引用计数归零销毁。
 */
public final class ClusterInvoker implements ClusterPort {

    private final Directories directory = new Directories();
    private final References references = new References();
    private final Map<String, Integer> weights = new HashMap<>();
    private final Map<String, Boolean> behaviors = new HashMap<>();
    private final Map<String, String> results = new HashMap<>();
    private final Map<String, List<String>> failures = new HashMap<>();
    private final Map<String, LoadBalancers.RoundRobin> roundRobbins = new HashMap<>();
    private final LoadBalancers.LeastActive leastActive = new LoadBalancers.LeastActive();
    private final Random random;

    public ClusterInvoker(long seed) {
        this.random = new Random(seed);
    }

    @Override
    public void register(String service, String provider, int weight) {
        if (weight <= 0) {
            throw new IllegalArgumentException("权重必须为正: " + weight);
        }
        directory.register(service, provider);
        weights.put(service + "/" + provider, weight);
        roundRobbins.remove(service);
    }

    @Override
    public void unregister(String service, String provider) {
        directory.unregister(service, provider);
        weights.remove(service + "/" + provider);
        roundRobbins.remove(service);
    }

    @Override
    public List<String> providers(String service) {
        return directory.providers(service);
    }

    @Override
    public List<String> directoryEvents(String service) {
        List<String> lines = new ArrayList<>();
        for (Directories.DirectoryEvent event : directory.events()) {
            if (event.service().equals(service)) {
                lines.add(event.type() + ":" + event.provider());
            }
        }
        return lines;
    }

    @Override
    public void behave(String provider, boolean ok, String result) {
        behaviors.put(provider, ok);
        results.put(provider, result);
    }

    @Override
    public String invoke(String service, String policy, int attempts) {
        List<String> providers = requireProviders(service);
        switch (policy) {
            case "FAILOVER" -> {
                Failovers.Attempt attempt = Failovers.invoke(providers, attempts,
                        provider -> call(provider));
                return attempt.provider() + ">" + attempt.result();
            }
            case "FAILFAST" -> {
                FailPolicies.Outcome outcome = FailPolicies.failfast(providers, this::call);
                return outcome.result();
            }
            case "FAILSAFE" -> {
                FailPolicies.Outcome outcome = FailPolicies.failsafe(providers, "DEFAULT", this::call);
                return outcome.result();
            }
            case "FORKING" -> {
                List<Forkings.Fork> forks = new ArrayList<>();
                int latency = 0;
                for (String provider : providers) {
                    forks.add(buildFork(provider, latency));
                    latency += 2;
                }
                return Forkings.invoke(forks, attempts);
            }
            default -> throw new IllegalArgumentException("未知策略拒绝: " + policy);
        }
    }

    @Override
    public List<String> broadcast(String service) {
        return Broadcasts.invoke(requireProviders(service), this::call);
    }

    @Override
    public List<String> failures(String provider) {
        return failures.getOrDefault(provider, List.of());
    }

    @Override
    public String select(String service, String strategy, String argument) {
        List<String> providers = requireProviders(service);
        return switch (strategy) {
            case "RANDOM" -> LoadBalancers.random(nodes(service), random);
            case "ROUND_ROBIN" -> roundRobin(service).next();
            case "LEAST_ACTIVE" -> leastActive.select(providers);
            case "CONSISTENT_HASH" -> LoadBalancers.consistentHash(providers, argument);
            default -> throw new IllegalArgumentException("未知策略拒绝: " + strategy);
        };
    }

    @Override
    public void active(String provider, int delta) {
        if (delta > 0) {
            leastActive.begin(provider);
        } else if (delta < 0) {
            leastActive.end(provider);
        }
    }

    @Override
    public void acquire(String key) {
        references.acquire(key);
    }

    @Override
    public void release(String key) {
        references.release(key);
    }

    @Override
    public int refCount(String key) {
        return references.count(key);
    }

    @Override
    public boolean refDestroyed(String key) {
        return references.destroyed(key);
    }

    @Override
    public List<String> contractShape() {
        return List.of("service", "method", "fullName", "methodType");
    }

    private Forkings.Fork buildFork(String provider, int latency) {
        boolean ok = behaviors.getOrDefault(provider, true);
        if (ok) {
            return new Forkings.Fork(provider, latency, true, results.getOrDefault(provider, "ok:" + provider));
        }
        return new Forkings.Fork(provider, latency, false, null);
    }

    private String call(String provider) {
        if (!behaviors.getOrDefault(provider, true)) {
            failures.computeIfAbsent(provider, key -> new ArrayList<>()).add("FAIL");
            throw new IllegalStateException("提供者失败: " + provider);
        }
        return results.getOrDefault(provider, "ok:" + provider);
    }

    private LoadBalancers.RoundRobin roundRobin(String service) {
        return roundRobbins.computeIfAbsent(service, key -> new LoadBalancers.RoundRobin(nodes(key)));
    }

    private List<LoadBalancers.Node> nodes(String service) {
        List<LoadBalancers.Node> nodes = new ArrayList<>();
        for (String provider : directory.providers(service)) {
            nodes.add(new LoadBalancers.Node(provider, weights.getOrDefault(service + "/" + provider, 1)));
        }
        return nodes;
    }

    private List<String> requireProviders(String service) {
        List<String> providers = directory.providers(service);
        if (providers.isEmpty()) {
            throw new IllegalArgumentException("空目录调用拒绝: " + service);
        }
        return providers;
    }
}

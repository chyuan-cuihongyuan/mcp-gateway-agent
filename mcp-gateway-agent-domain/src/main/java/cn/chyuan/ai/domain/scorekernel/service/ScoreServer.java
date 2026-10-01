package cn.chyuan.ai.domain.scorekernel.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调度评分组合实现（工单 1195 FD8，kubernetes 思想）。
 * bind 管线：谓词过滤→评分最高落位→空集时按节点逐个尝试抢占
 * （受害者优先级最低优先、同优先级创建序最早，D6）；队列按优先级出队同优先级 FIFO。
 */
public final class ScoreServer implements ScorePort {

    private final Cluster cluster = new Cluster();
    private final Feasibility feasibility = new Feasibility(cluster);
    private final LeastRequested scoring = new LeastRequested(cluster);
    private final Map<String, PodSpec> pods = new LinkedHashMap<>();
    private final Map<String, String> bindings = new HashMap<>();
    private final List<PodSpec> queue = new ArrayList<>();
    private int seq;

    @Override
    public void node(String name, int cpu, int mem) {
        cluster.node(name, cpu, mem);
    }

    @Override
    public void label(String node, String key, String value) {
        cluster.label(node, key, value);
    }

    @Override
    public void taint(String node, String key) {
        cluster.taint(node, key);
    }

    @Override
    public void submit(String pod, int priority, int cpu, int mem) {
        requireNew(pod);
        if (cpu <= 0 || mem <= 0) {
            throw new IllegalArgumentException("资源请求须为正");
        }
        PodSpec spec = new PodSpec(pod, priority, ++seq, cpu, mem);
        pods.put(pod, spec);
        queue.add(spec);
    }

    @Override
    public void selector(String pod, String key, String value) {
        require(pod).selector.put(key, value);
    }

    @Override
    public void tolerate(String pod, String taint) {
        require(pod).tolerations.add(taint);
    }

    @Override
    public void affinity(String pod, String key, String value) {
        require(pod).affinity.put(key, value);
    }

    @Override
    public void antiAffinity(String pod, String labelKey) {
        require(pod).antiLabels.add(labelKey);
    }

    @Override
    public List<String> feasible(String pod) {
        return feasibility.feasible(require(pod));
    }

    @Override
    public int score(String node, String pod) {
        return scoring.score(node, require(pod));
    }

    @Override
    public String bind(String pod) {
        PodSpec spec = require(pod);
        if (bindings.containsKey(pod)) {
            throw new IllegalArgumentException("重复绑定: " + pod);
        }
        List<String> candidates = feasibility.feasible(spec);
        if (!candidates.isEmpty()) {
            String best = bestByScore(candidates, spec);
            cluster.bind(best, pod, spec.cpu, spec.mem);
            bindings.put(pod, best);
            queue.remove(spec);
            return best;
        }
        return preemptAndBind(spec);
    }

    /** 抢占：按评分降序逐节点尝试受害者腾退；无受害可选拒绝 */
    private String preemptAndBind(PodSpec spec) {
        List<String> nodes = cluster.nodeNames().stream()
                .sorted(Comparator.comparingInt((String n) -> scoring.score(n, spec)).reversed())
                .toList();
        for (String nodeName : nodes) {
            Cluster.Node node = cluster.require(nodeName);
            List<PodSpec> victims = new ArrayList<>();
            int freedCpu = 0;
            int freedMem = 0;
            List<PodSpec> candidates = node.bound.stream()
                    .map(pods::get)
                    .filter(v -> v.priority < spec.priority)
                    .sorted(Comparator.comparingInt((PodSpec v) -> v.priority).thenComparingInt(v -> v.seq))
                    .toList();            for (PodSpec victim : candidates) {
                victims.add(victim);
                freedCpu += victim.cpu;
                freedMem += victim.mem;
                if (node.remainingCpu() + freedCpu >= spec.cpu
                        && node.remainingMem() + freedMem >= spec.mem) {
                    for (PodSpec evicted : victims) {
                        cluster.unbind(nodeName, evicted.name, evicted.cpu, evicted.mem);
                        bindings.remove(evicted.name);
                        queue.add(evicted);
                    }
                    cluster.bind(nodeName, spec.name, spec.cpu, spec.mem);
                    bindings.put(spec.name, nodeName);
                    queue.remove(spec);
                    return nodeName;
                }
            }
        }
        throw new IllegalStateException("无受害可选: " + spec.name);
    }

    @Override
    public List<String> schedule() {
        List<PodSpec> ordered = queue.stream()
                .sorted(Comparator.comparingInt((PodSpec s) -> s.priority).reversed()
                        .thenComparingInt(s -> s.seq))
                .toList();
        queue.clear();
        List<String> bound = new ArrayList<>();
        List<PodSpec> deferred = new ArrayList<>();
        for (PodSpec spec : ordered) {
            try {
                bind(spec.name);
                bound.add(spec.name);
            } catch (RuntimeException ignored) {
                deferred.add(spec);
            }
        }
        queue.addAll(deferred);
        return bound;
    }

    @Override
    public int queued() {
        return queue.size();
    }

    @Override
    public String boundTo(String pod) {
        String node = bindings.get(pod);
        if (node == null) {
            throw new IllegalArgumentException("未绑定: " + pod);
        }
        return node;
    }

    @Override
    public List<String> queueShape() {
        return List.of("g", "p", "runq");
    }

    private String bestByScore(List<String> candidates, PodSpec spec) {
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (String node : candidates) {
            int score = scoring.score(node, spec);
            if (score > bestScore) {
                bestScore = score;
                best = node;
            }
        }
        return best;
    }

    private PodSpec require(String pod) {
        PodSpec spec = pods.get(pod);
        if (spec == null) {
            throw new IllegalArgumentException("未知 pod: " + pod);
        }
        return spec;
    }

    private void requireNew(String pod) {
        if (pod == null || pod.isBlank()) {
            throw new IllegalArgumentException("pod 名不得为空");
        }
        if (pods.containsKey(pod)) {
            throw new IllegalArgumentException("重复 pod: " + pod);
        }
    }
}

package cn.chyuan.ai.domain.clusterkernel.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 负载均衡（工单 1087 ER6，dubbo 思想）。
 * random 按权重种子化/roundrobin 权重展开轮转/leastactive 最少活跃平局取首/
 * consistenthash 虚节点 160 一致性哈希同参数稳定命中。
 */
public final class LoadBalancers {

    /** 加权节点 */
    public record Node(String provider, int weight) {
    }

    private LoadBalancers() {
    }

    /** 随机：按权重倾斜分布（种子注入确定性） */
    public static String random(List<Node> nodes, Random random) {
        requireNodes(nodes);
        int total = 0;
        for (Node node : nodes) {
            total += node.weight();
        }
        int cursor = random.nextInt(total);
        for (Node node : nodes) {
            cursor -= node.weight();
            if (cursor < 0) {
                return node.provider();
            }
        }
        return nodes.get(nodes.size() - 1).provider();
    }

    /** 轮询：按权重展开循环取用 */
    public static final class RoundRobin {
        private final List<String> expanded = new ArrayList<>();
        private final AtomicInteger cursor = new AtomicInteger();

        public RoundRobin(List<Node> nodes) {
            requireNodes(nodes);
            for (Node node : nodes) {
                for (int count = 0; count < Math.max(1, node.weight()); count++) {
                    expanded.add(node.provider());
                }
            }
        }

        public String next() {
            return expanded.get(Math.floorMod(cursor.getAndIncrement(), expanded.size()));
        }
    }

    /** 最少活跃：活跃计数 begin 递增 end 递减，取最小者（平局取列表序首个） */
    public static final class LeastActive {
        private final Map<String, AtomicInteger> active = new ConcurrentHashMap<>();

        public void begin(String provider) {
            active.computeIfAbsent(provider, key -> new AtomicInteger()).incrementAndGet();
        }

        public void end(String provider) {
            AtomicInteger counter = active.get(provider);
            if (counter == null) {
                throw new IllegalArgumentException("未 begin 拒绝 end: " + provider);
            }
            counter.decrementAndGet();
        }

        public String select(List<String> providers) {
            if (providers == null || providers.isEmpty()) {
                throw new IllegalArgumentException("空候选拒绝");
            }
            String best = null;
            int bestActive = Integer.MAX_VALUE;
            for (String provider : providers) {
                int current = active.getOrDefault(provider, new AtomicInteger()).get();
                if (current < bestActive) {
                    bestActive = current;
                    best = provider;
                }
            }
            return best;
        }
    }

    /** 一致性哈希：每提供者 160 虚节点（dubbo 默认 replicas），同参数稳定命中 */
    public static String consistentHash(List<String> providers, String argument) {
        if (providers == null || providers.isEmpty()) {
            throw new IllegalArgumentException("空候选拒绝");
        }
        if (argument == null || argument.isBlank()) {
            throw new IllegalArgumentException("哈希参数不能为空");
        }
        TreeMap<Long, String> ring = new TreeMap<>();
        for (String provider : providers) {
            for (int replica = 0; replica < 160; replica++) {
                ring.put(hash(provider + "#" + replica), provider);
            }
        }
        long key = hash(argument);
        Map.Entry<Long, String> ceiling = ring.ceilingEntry(key);
        return ceiling != null ? ceiling.getValue() : ring.firstEntry().getValue();
    }

    private static long hash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            long value = 0;
            for (int index = 0; index < 8; index++) {
                value = (value << 8) | (bytes[index] & 0xFF);
            }
            return value;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static void requireNodes(List<Node> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("空节点拒绝");
        }
        for (Node node : nodes) {
            if (node.weight() <= 0) {
                throw new IllegalArgumentException("权重必须为正: " + node.provider());
            }
        }
    }
}

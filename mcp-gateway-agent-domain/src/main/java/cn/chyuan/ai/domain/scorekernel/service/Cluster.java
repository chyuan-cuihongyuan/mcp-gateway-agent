package cn.chyuan.ai.domain.scorekernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 集群节点与绑定（工单 1188 FD1 / 1193 FD6，kubernetes 思想）。
 * 节点注册容量与标签污点、重复注册拒绝；绑定占容、重复绑定拒绝、解绑腾退。
 */
public final class Cluster {

    /** 节点快照 */
    public static final class Node {
        public final String name;
        public final int cpuCapacity;
        public final int memCapacity;
        public final Map<String, String> labels = new LinkedHashMap<>();
        public final Set<String> taints = new LinkedHashSet<>();
        public final List<String> bound = new ArrayList<>();
        int cpuUsed;
        int memUsed;

        Node(String name, int cpuCapacity, int memCapacity) {
            this.name = name;
            this.cpuCapacity = cpuCapacity;
            this.memCapacity = memCapacity;
        }

        public int remainingCpu() {
            return cpuCapacity - cpuUsed;
        }

        public int remainingMem() {
            return memCapacity - memUsed;
        }
    }

    private final Map<String, Node> nodes = new LinkedHashMap<>();

    /** 注册节点：重复名/空名/非正容量拒绝 */
    public Node node(String name, int cpu, int mem) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("节点名不得为空");
        }
        if (nodes.containsKey(name)) {
            throw new IllegalArgumentException("重复节点: " + name);
        }
        if (cpu <= 0 || mem <= 0) {
            throw new IllegalArgumentException("容量须为正");
        }
        Node node = new Node(name, cpu, mem);
        nodes.put(name, node);
        return node;
    }

    public Node require(String name) {
        Node node = nodes.get(name);
        if (node == null) {
            throw new IllegalArgumentException("未知节点: " + name);
        }
        return node;
    }

    /** 节点名字典序 */
    public List<String> nodeNames() {
        return nodes.keySet().stream().sorted().toList();
    }

    public List<Node> allNodes() {
        return nodes.values().stream().sorted((a, b) -> a.name.compareTo(b.name)).toList();
    }

    public void label(String node, String key, String value) {
        require(node).labels.put(key, value);
    }

    public void taint(String node, String key) {
        require(node).taints.add(key);
    }

    /** 绑定占容；容量不足拒绝（进入前置过滤后不应发生） */
    public void bind(String node, String pod, int cpu, int mem) {
        Node n = require(node);
        if (cpu > n.remainingCpu() || mem > n.remainingMem()) {
            throw new IllegalStateException("绑定超容: " + node);
        }
        n.bound.add(pod);
        n.cpuUsed += cpu;
        n.memUsed += mem;
    }

    /** 解绑腾退；未知绑定拒绝 */
    public void unbind(String node, String pod, int cpu, int mem) {
        Node n = require(node);
        if (!n.bound.remove(pod)) {
            throw new IllegalArgumentException("未知绑定: " + pod + "@" + node);
        }
        n.cpuUsed -= cpu;
        n.memUsed -= mem;
    }
}

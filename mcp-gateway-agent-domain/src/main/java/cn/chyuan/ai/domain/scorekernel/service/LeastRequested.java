package cn.chyuan.ai.domain.scorekernel.service;

/**
 * LeastRequested 优先级评分（工单 1191 FD4，kubernetes 思想）。
 * 剩余资源比率均值×100 归一化 0-100（cpu/mem 等权）；
 * 节点亲和命中加分（每命中 +20），上不封顶前先归一截断至 100。
 */
public final class LeastRequested {

    static final int AFFINITY_BONUS = 20;

    private final Cluster cluster;

    public LeastRequested(Cluster cluster) {
        this.cluster = cluster;
    }

    /** 评分：剩余比率均值×100 + 亲和加分×命中，截断 100 */
    public int score(String nodeName, PodSpec pod) {
        Cluster.Node node = cluster.require(nodeName);
        double cpuRatio = (double) node.remainingCpu() / node.cpuCapacity;
        double memRatio = (double) node.remainingMem() / node.memCapacity;
        int base = (int) Math.round((cpuRatio + memRatio) / 2 * 100);
        return Math.min(100, base + Feasibility.affinityHits(node, pod) * AFFINITY_BONUS);
    }
}

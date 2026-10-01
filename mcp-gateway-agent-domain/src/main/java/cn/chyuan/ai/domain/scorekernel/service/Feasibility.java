package cn.chyuan.ai.domain.scorekernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 谓词过滤（工单 1189 FD2 / 1190 FD3，kubernetes predicates 思想）。
 * 资源不足滤除/污点不容忍滤除/节点选择器不匹配滤除/反亲和标签命中滤除；
 * 全滤除得空可调度集（非异常）；亲和匹配供评分加分。
 */
public final class Feasibility {

    private final Cluster cluster;

    public Feasibility(Cluster cluster) {
        this.cluster = cluster;
    }

    /** 可调度节点：资源+污点+选择器+反亲和全过，字典序 */
    public List<String> feasible(PodSpec pod) {
        List<String> out = new ArrayList<>();
        for (Cluster.Node node : cluster.allNodes()) {
            if (fits(node, pod) && tolerates(node, pod) && selectorMatches(node, pod) && antiAbsent(node, pod)) {
                out.add(node.name);
            }
        }
        return out;
    }

    /** 资源谓词 */
    static boolean fits(Cluster.Node node, PodSpec pod) {
        return pod.cpu <= node.remainingCpu() && pod.mem <= node.remainingMem();
    }

    /** 污点谓词：节点污点须全部被容忍 */
    static boolean tolerates(Cluster.Node node, PodSpec pod) {
        return node.taints.stream().allMatch(pod.tolerations::contains);
    }

    /** 节点选择器谓词：声明键值全匹配 */
    static boolean selectorMatches(Cluster.Node node, PodSpec pod) {
        return pod.selector.entrySet().stream()
                .allMatch(e -> valueEquals(node.labels.get(e.getKey()), e.getValue()));
    }

    /** 反亲和谓词：节点不得持有声明的反亲和标签键 */
    static boolean antiAbsent(Cluster.Node node, PodSpec pod) {
        return pod.antiLabels.stream().noneMatch(node.labels::containsKey);
    }

    /** 亲和命中数：节点标签与亲和声明逐条匹配（供评分加分） */
    static int affinityHits(Cluster.Node node, PodSpec pod) {
        return (int) pod.affinity.entrySet().stream()
                .filter(e -> valueEquals(node.labels.get(e.getKey()), e.getValue()))
                .count();
    }

    private static boolean valueEquals(String actual, String expected) {
        return expected.equals(actual);
    }
}

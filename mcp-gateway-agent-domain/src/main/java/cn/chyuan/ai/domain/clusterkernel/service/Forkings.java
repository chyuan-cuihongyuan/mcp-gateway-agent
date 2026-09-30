package cn.chyuan.ai.domain.clusterkernel.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * forking 并行调用（工单 1085 ER4，dubbo 思想）。
 * 并行 N 路取首成功（最快成功者胜出、最慢路结果废弃）/全部失败整体失败/并行度上限钳制。
 */
public final class Forkings {

    /** 并行分叉：提供者 + 延迟 tick + 结果 + 成败 */
    public record Fork(String provider, int latencyTicks, boolean success, String result) {
    }

    private Forkings() {
    }

    /** 取首成功：参与路数 = min(parallelism, 分叉数)；按延迟升序评估，首个成功者胜出 */
    public static String invoke(List<Fork> forks, int parallelism) {
        if (forks == null || forks.isEmpty()) {
            throw new IllegalArgumentException("空分叉拒绝");
        }
        if (parallelism <= 0) {
            throw new IllegalArgumentException("并行度必须为正: " + parallelism);
        }
        int limit = Math.min(parallelism, forks.size());
        List<Fork> chosen = new ArrayList<>(forks.subList(0, limit));
        chosen.sort(Comparator.comparingInt(Fork::latencyTicks));
        List<String> failed = new ArrayList<>();
        for (Fork fork : chosen) {
            if (fork.success()) {
                return fork.result();
            }
            failed.add(fork.provider());
        }
        throw new Failovers.ClusterException("forking 全部失败", failed);
    }
}

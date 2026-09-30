package cn.chyuan.ai.domain.clusterkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * failover 失败转移（工单 1083 ER2，dubbo 思想）。
 * 失败换下一提供者重试/重试次数耗尽整体失败/重试不回退已失败提供者（顺序推进）/成功即返。
 */
public final class Failovers {

    /** 调用面：抛异常视为该提供者调用失败 */
    public interface Call {
        String call(String provider);
    }

    /** 集群调用整体失败：携带已失败提供者清单 */
    public static final class ClusterException extends RuntimeException {
        private final List<String> failedProviders;

        public ClusterException(String message, List<String> failedProviders) {
            super(message);
            this.failedProviders = List.copyOf(failedProviders);
        }

        public List<String> failedProviders() {
            return failedProviders;
        }
    }

    /** 一次成功尝试：提供者 + 结果 + 此前失败清单 */
    public record Attempt(String provider, String result, List<String> priorFailures) {
    }

    private Failovers() {
    }

    /** 顺序重试：候选序逐一尝试（总尝试 = min(retries+1, 候选数)），耗尽抛 ClusterException */
    public static Attempt invoke(List<String> providers, int retries, Call call) {
        if (providers == null || providers.isEmpty()) {
            throw new IllegalArgumentException("空目录调用拒绝");
        }
        if (retries < 0) {
            throw new IllegalArgumentException("重试次数不可为负: " + retries);
        }
        List<String> failed = new ArrayList<>();
        int limit = Math.min(retries + 1, providers.size());
        for (int index = 0; index < limit; index++) {
            String provider = providers.get(index);
            try {
                return new Attempt(provider, call.call(provider), List.copyOf(failed));
            } catch (RuntimeException exception) {
                failed.add(provider);
            }
        }
        throw new ClusterException("重试耗尽", failed);
    }
}

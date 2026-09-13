package cn.chyuan.ai.domain.configcenter.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配置健康评估（工单 0257 AG7，借鉴 Argo CD 同步健康四态）—
 * Healthy=期望与实际一致；Degraded=schema 校验失败或漂移超阈；
 * Progressing=发布进行中；Suspended=灰度暂停或手动挂起。
 * 状态判定优先级：Suspended > Degraded > Progressing > Healthy（最严重优先呈现）。
 * 输入由事件侧维护（发布开始/结束、门拦截、漂移报告、灰度暂停）。
 *
 * @author chyuan
 */
@Service
public class ConfigHealthEvaluator {

    public static final String HEALTHY = "Healthy";
    public static final String DEGRADED = "Degraded";
    public static final String PROGRESSING = "Progressing";
    public static final String SUSPENDED = "Suspended";

    /** 命名空间健康输入（事件侧累积的可观测状态） */
    public record HealthInputs(boolean publishing, boolean gateFailed, boolean driftOverThreshold,
            boolean suspended) {
    }

    /** 单命名空间评估（纯函数） */
    public String evaluate(HealthInputs inputs) {
        if (inputs.suspended()) {
            return SUSPENDED;
        }
        if (inputs.gateFailed() || inputs.driftOverThreshold()) {
            return DEGRADED;
        }
        if (inputs.publishing()) {
            return PROGRESSING;
        }
        return HEALTHY;
    }

    /** 全局汇总 */
    public record HealthSummary(int healthy, int degraded, int progressing, int suspended, int total) {
    }

    /**
     * 命名空间健康服务（事件侧状态维护 + 查询）
     */
    @Service
    public static class ConfigHealthService {

        private final Map<String, HealthInputs> states = new ConcurrentHashMap<>();
        private final ConfigHealthEvaluator evaluator = new ConfigHealthEvaluator();

        /** 事件侧更新（全量覆盖该命名空间输入） */
        public void update(String namespace, HealthInputs inputs) {
            states.put(namespace, inputs);
        }

        public String statusOf(String namespace) {
            HealthInputs inputs = states.get(namespace);
            return inputs == null ? HEALTHY : evaluator.evaluate(inputs);
        }

        public Map<String, String> allStatuses() {
            Map<String, String> out = new java.util.TreeMap<>();
            states.keySet().forEach(ns -> out.put(ns, statusOf(ns)));
            return out;
        }

        public HealthSummary summary() {
            int healthy = 0;
            int degraded = 0;
            int progressing = 0;
            int suspended = 0;
            for (String status : allStatuses().values()) {
                switch (status) {
                    case DEGRADED -> degraded++;
                    case PROGRESSING -> progressing++;
                    case SUSPENDED -> suspended++;
                    default -> healthy++;
                }
            }
            return new HealthSummary(healthy, degraded, progressing, suspended, states.size());
        }
    }
}

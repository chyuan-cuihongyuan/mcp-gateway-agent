package cn.chyuan.ai.domain.llmchannel.service;

/**
 * 渠道健康分加权纯函数（工单 0159，0-100 分）
 *
 * <p>三成分加权（权重配置化，和必须为 1）：
 * ①错误分：近 N 次账本成功率 × 100（无账本数据记 60 中性偏保守）；
 * ②探测分：探测成功率 0-100 直填；缺探测项降级——按 50 中性计入（权重照乘，总分被拉低）；
 * ③延迟分：clamp(100 × (1 - avgLatency / latencyRefMs))，参考延迟（latencyRefMs）可配置，
 *   达到参考延迟得 0 分、零延迟得满分；无延迟数据记 50 中性。
 * 总分钳制 [0,100]。权重非法（负值/和≠1）在装配期即抛异常（fail-fast）。
 *
 * @author chyuan
 */
public final class ChannelHealthScore {

    /** 缺数据项的中性分 */
    public static final double NEUTRAL_SCORE = 50.0;

    /** 无账本数据时的错误分（略高于中性，不冤枉冷启动渠道） */
    public static final double NO_DATA_ERROR_SCORE = 60.0;

    private ChannelHealthScore() {
        // 纯函数工具类，禁止实例化
    }

    /** 权重三元组（error + probe + latency = 1） */
    public record Weights(double error, double probe, double latency) {
    }

    /** 校验权重配置：非负且和为 1（±1e-9），非法抛 IllegalArgumentException */
    public static Weights validateWeights(double error, double probe, double latency) {
        if (error < 0 || probe < 0 || latency < 0) {
            throw new IllegalArgumentException("健康分权重不可为负: error=" + error + ", probe=" + probe
                    + ", latency=" + latency);
        }
        double sum = error + probe + latency;
        if (Math.abs(sum - 1.0) > 1e-9) {
            throw new IllegalArgumentException("健康分权重和必须为 1（当前 " + sum + "）");
        }
        return new Weights(error, probe, latency);
    }

    /**
     * 计算健康分。
     *
     * @param recentTotal        近 N 次账本调用总数
     * @param recentFailures     近 N 次失败数
     * @param probeSuccessPercent 探测成功率（0-100；null=缺探测项，按中性 50 降级计入）
     * @param avgLatencyMs       平均延迟毫秒（null=无数据，按中性 50）
     * @param latencyRefMs       参考延迟毫秒（达到即 0 分）
     * @param weights            已校验权重
     * @return 0-100 钳制后的健康分
     */
    public static double score(int recentTotal, int recentFailures,
            Double probeSuccessPercent, Long avgLatencyMs, long latencyRefMs, Weights weights) {
        double errorScore = recentTotal <= 0
                ? NO_DATA_ERROR_SCORE
                : clamp((1.0 - (double) recentFailures / recentTotal) * 100.0);
        double probeScore = probeSuccessPercent == null ? NEUTRAL_SCORE : clamp(probeSuccessPercent);
        double latencyScore = avgLatencyMs == null
                ? NEUTRAL_SCORE
                : clamp(100.0 * (1.0 - avgLatencyMs / (double) Math.max(1, latencyRefMs)));
        return clamp(weights.error() * errorScore + weights.probe() * probeScore
                + weights.latency() * latencyScore);
    }

    /** 0-100 钳制 */
    public static double clamp(double value) {
        return Math.max(0.0, Math.min(100.0, value));
    }

    /**
     * 调度降权排序（工单 0159）：低于阈值的渠道稳定排到候选尾部（分组内相对顺序不变），
     * 不剔除——低分渠道仍可作为最后兜底。阈值 <=0 = 关闭降权。
     *
     * @param channels 候选渠道（调度序）
     * @param scoreOf  渠道 id → 健康分
     * @param threshold 降权阈值
     * @return 重排后的候选（健康在前、低分在尾）
     */
    public static java.util.List<cn.chyuan.ai.domain.llmchannel.model.valobj.LlmChannelVO> demoteBelow(
            java.util.List<cn.chyuan.ai.domain.llmchannel.model.valobj.LlmChannelVO> channels,
            java.util.function.ToLongFunction<cn.chyuan.ai.domain.llmchannel.model.valobj.LlmChannelVO> keyOf,
            java.util.function.LongToDoubleFunction scoreOf,
            int threshold) {
        if (channels == null || channels.isEmpty() || threshold <= 0) {
            return channels;
        }
        java.util.List<cn.chyuan.ai.domain.llmchannel.model.valobj.LlmChannelVO> healthy = new java.util.ArrayList<>();
        java.util.List<cn.chyuan.ai.domain.llmchannel.model.valobj.LlmChannelVO> degraded = new java.util.ArrayList<>();
        for (cn.chyuan.ai.domain.llmchannel.model.valobj.LlmChannelVO channel : channels) {
            if (scoreOf.applyAsDouble(keyOf.applyAsLong(channel)) < threshold) {
                degraded.add(channel);
            } else {
                healthy.add(channel);
            }
        }
        healthy.addAll(degraded);
        return healthy;
    }
}

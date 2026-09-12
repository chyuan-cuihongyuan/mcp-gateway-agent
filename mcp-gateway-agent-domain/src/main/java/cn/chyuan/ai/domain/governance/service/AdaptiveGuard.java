package cn.chyuan.ai.domain.governance.service;

/**
 * 负载水位自适应保护纯函数（工单 0227 AD8，借鉴 Sentinel system adaptive 简化）—
 * 输入水位（并发使用率 0-1 与排队深度），输出三档判定：
 * GREEN 放行 / YELLOW 降额（对半放行）/ RED 拒新。阈值配置化并做一致性钳制。
 *
 * @author chyuan
 */
public final class AdaptiveGuard {

    public static final String LEVEL_GREEN = "GREEN";
    public static final String LEVEL_YELLOW = "YELLOW";
    public static final String LEVEL_RED = "RED";

    /** 水位输入 */
    public record LoadWatermark(double inFlightRatio, int queueDepth) {
        public LoadWatermark {
            if (inFlightRatio < 0) {
                inFlightRatio = 0;
            }
            if (inFlightRatio > 1) {
                inFlightRatio = 1;
            }
            if (queueDepth < 0) {
                queueDepth = 0;
            }
        }
    }

    /** 阈值组（yellow < red；脏值回退默认 0.7/0.9，排队深阈值默认 100） */
    public record Thresholds(double yellowRatio, double redRatio, int maxQueueDepth) {
        public static final Thresholds DEFAULT = new Thresholds(0.7d, 0.9d, 100);

        public Thresholds {
            if (yellowRatio <= 0) {
                yellowRatio = DEFAULT.yellowRatio;
            }
            if (redRatio <= yellowRatio) {
                redRatio = Math.max(yellowRatio + 0.01d, DEFAULT.redRatio);
            }
            if (maxQueueDepth <= 0) {
                maxQueueDepth = DEFAULT.maxQueueDepth;
            }
        }
    }

    private AdaptiveGuard() {
    }

    /** 三档判定：usage ≥ red 或排队超限 → RED；usage ≥ yellow 或排队过半 → YELLOW；否则 GREEN */
    public static String grade(LoadWatermark watermark, Thresholds thresholds) {
        Thresholds t = thresholds == null ? Thresholds.DEFAULT : thresholds;
        if (watermark.inFlightRatio() >= t.redRatio() || watermark.queueDepth() >= t.maxQueueDepth()) {
            return LEVEL_RED;
        }
        if (watermark.inFlightRatio() >= t.yellowRatio()
                || watermark.queueDepth() >= t.maxQueueDepth() / 2) {
            return LEVEL_YELLOW;
        }
        return LEVEL_GREEN;
    }

    /** RED 是否拒绝 */
    public static boolean shouldReject(String level) {
        return LEVEL_RED.equals(level);
    }

    /** YELLOW 是否降额放行（对半策略：偶数序号放行；GREEN 恒放行，RED 恒拒） */
    public static boolean shouldAdmit(String level, long admissionSeq) {
        if (LEVEL_RED.equals(level)) {
            return false;
        }
        if (LEVEL_YELLOW.equals(level)) {
            return admissionSeq % 2 == 0;
        }
        return true;
    }
}

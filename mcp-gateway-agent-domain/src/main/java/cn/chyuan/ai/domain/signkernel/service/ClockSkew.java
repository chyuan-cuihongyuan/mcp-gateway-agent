package cn.chyuan.ai.domain.signkernel.service;

/**
 * 时钟窗口（工单 1018 EJ6，minio 时钟偏斜思想）。
 * 偏斜容差窗口内放行/超窗拒绝/窗口边界含端点。
 */
public final class ClockSkew {

    public static final long DEFAULT_TOLERANCE_MS = 300_000;

    private ClockSkew() {
    }

    /** 偏斜核验：|server-request| ≤ 容差放行（含端点），超窗拒绝 */
    public static void check(long requestMs, long serverNowMs, long toleranceMs) {
        if (toleranceMs < 0) {
            throw new IllegalArgumentException("容差为负: " + toleranceMs);
        }
        long skew = Math.abs(serverNowMs - requestMs);
        if (skew > toleranceMs) {
            throw new IllegalStateException("时钟偏斜超窗: " + skew + "ms > " + toleranceMs + "ms"
                    + (serverNowMs > requestMs ? "（请求超前）" : "（请求滞后）"));
        }
    }
}

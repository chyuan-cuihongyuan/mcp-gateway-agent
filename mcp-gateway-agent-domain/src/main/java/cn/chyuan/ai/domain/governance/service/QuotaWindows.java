package cn.chyuan.ai.domain.governance.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * 预算滚动窗口起点纯函数（工单 0158，LiteLLM 滚动预算口径裁剪）
 *
 * <p>口径（工单验收锚点）：
 * ①滑动窗口=回溯窗：起点 = now - 窗口时长，随请求时点前移，无固定窗口重置套利；
 * ②周窗起点口径=滚动 7 天（now-7d），不取自然周周一；
 * ③月窗=java.time minusMonths(1) 自然月回溯，自动处理月长不一（28/30/31 天，1/31→2/28）；
 * ④窗口起点含边界：下游 sumCostSince/countSince 用 created_at >= since（起点记录计入窗口）；
 * ⑤未配置（null/空白/未知值）返回 null，调用方沿用旧固定窗口行为（兼容存量）。
 *
 * @author chyuan
 */
public final class QuotaWindows {

    /** 日窗口（回溯 24 小时） */
    public static final String WINDOW_DAY = "DAY";

    /** 周窗口（回溯 7 天，滚动口径而非自然周） */
    public static final String WINDOW_WEEK = "WEEK";

    /** 月窗口（自然月回溯，处理月长不一） */
    public static final String WINDOW_MONTH = "MONTH";

    private QuotaWindows() {
        // 纯函数工具类，禁止实例化
    }

    /**
     * 计算滚动窗口起点。
     *
     * @param now        当前时点
     * @param windowType DAY / WEEK / MONTH（大小写不敏感）；其他返回 null=旧行为
     * @return 窗口起点（含边界口径 created_at >= 起点）；未配置返回 null
     */
    public static Date startOf(Date now, String windowType) {
        if (now == null || windowType == null || windowType.isBlank()) {
            return null;
        }
        Instant instant = now.toInstant();
        return switch (windowType.trim().toUpperCase(java.util.Locale.ROOT)) {
            case WINDOW_DAY -> Date.from(instant.minus(1, ChronoUnit.DAYS));
            case WINDOW_WEEK -> Date.from(instant.minus(7, ChronoUnit.DAYS));
            case WINDOW_MONTH -> Date.from(instant.atZone(ZoneId.systemDefault()).toLocalDateTime()
                    .minusMonths(1).atZone(ZoneId.systemDefault()).toInstant());
            default -> null;
        };
    }

    /** 是否为受支持的滑动窗口类型（调用方决定走滑动路径还是旧固定窗口路径） */
    public static boolean isSliding(String windowType) {
        return startOf(new Date(), windowType) != null;
    }
}

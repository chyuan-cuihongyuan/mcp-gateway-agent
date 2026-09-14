package cn.chyuan.ai.domain.toolchain.service;

import cn.chyuan.ai.domain.toolchain.model.valobj.ToolCallRecordVO;
import cn.chyuan.ai.domain.toolchain.model.valobj.ToolHealthVO;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 工具健康度统计（工单 0338 AP8）。
 * 基于调用留痕的滑动窗口统计：调用数/错误率（TIMEOUT+ERROR）/P50 与 P95 延迟
 * /最近失败列表 → 评级（错误率阈值 + 样本下限）。domain 纯函数。
 */
public class ToolHealthStats {

    private static final String UNHEALTHY = "Unhealthy";
    private static final String DEGRADED = "Degraded";
    private static final String HEALTHY = "Healthy";

    private final double degradedRate;
    private final double unhealthyRate;
    private final int minSamples;

    public ToolHealthStats(double degradedRate, double unhealthyRate, int minSamples) {
        if (degradedRate <= 0 || unhealthyRate <= degradedRate || degradedRate > 1) {
            throw new IllegalArgumentException("阈值应满足 0 < degraded < unhealthy <= 1");
        }
        if (minSamples <= 0) {
            throw new IllegalArgumentException("样本下限必须为正数");
        }
        this.degradedRate = degradedRate;
        this.unhealthyRate = unhealthyRate;
        this.minSamples = minSamples;
    }

    /** 统计某工具窗口内健康度（时间窗 [fromMs, toMs]，可空=全量） */
    public ToolHealthVO evaluate(String toolName, List<ToolCallRecordVO> records,
                                 Long fromMs, Long toMs, int recentFailureLimit) {
        List<ToolCallRecordVO> window = new ArrayList<>();
        for (ToolCallRecordVO record : records == null ? List.<ToolCallRecordVO>of() : records) {
            if (!toolName.equals(record.getToolName())) {
                continue;
            }
            if (fromMs != null && record.getAtMs() < fromMs) {
                continue;
            }
            if (toMs != null && record.getAtMs() > toMs) {
                continue;
            }
            window.add(record);
        }
        int total = window.size();
        long errors = window.stream()
                .filter(r -> "ERROR".equals(r.getStatus()) || "TIMEOUT".equals(r.getStatus()))
                .count();
        List<Long> latencies = window.stream().map(ToolCallRecordVO::getCostMs)
                .sorted(Comparator.naturalOrder()).toList();
        List<String> failures = window.stream()
                .filter(r -> "ERROR".equals(r.getStatus()) || "TIMEOUT".equals(r.getStatus()))
                .sorted(Comparator.comparingLong(ToolCallRecordVO::getAtMs).reversed())
                .limit(recentFailureLimit)
                .map(r -> r.getAtMs() + ":" + r.getStatus())
                .toList();
        double errorRate = total == 0 ? 0 : (double) errors / total;
        return ToolHealthVO.builder()
                .toolName(toolName)
                .totalCalls(total)
                .errorRate(errorRate)
                .p50Ms(percentile(latencies, 0.5))
                .p95Ms(percentile(latencies, 0.95))
                .recentFailures(failures)
                .grade(grade(errorRate, total))
                .build();
    }

    /** 评级：样本不足视为 Healthy（不误伤）；超阈值按错误率降级 */
    String grade(double errorRate, int totalCalls) {
        if (totalCalls < minSamples) {
            return HEALTHY;
        }
        if (errorRate >= unhealthyRate) {
            return UNHEALTHY;
        }
        if (errorRate >= degradedRate) {
            return DEGRADED;
        }
        return HEALTHY;
    }

    /** 分位数（最近邻秩：ceil(p*n)，空为 0） */
    static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int rank = (int) Math.ceil(p * sorted.size());
        rank = Math.max(1, Math.min(sorted.size(), rank));
        return sorted.get(rank - 1);
    }
}

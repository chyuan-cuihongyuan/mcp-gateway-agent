package cn.chyuan.ai.domain.usage.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用量日聚合值对象（工单 0046；唯一维度 stat_date × virtual_key_id × tool_or_model × channel_id）
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyUsageVO {

    /** 聚合日期（yyyy-MM-dd） */
    private String statDate;

    private Long virtualKeyId;

    private String toolOrModel;

    private String channelId;

    private Long callCount;

    private Long failCount;

    private Long totalDurationMs;

    private Long tokenSum;

    /** 增量形态（写路径）：在既有行上累加 */
    public static DailyUsageVO deltaOf(String statDate, Long virtualKeyId, String toolOrModel,
            String channelId, boolean success, long durationMs, long tokens) {
        return DailyUsageVO.builder()
                .statDate(statDate)
                .virtualKeyId(virtualKeyId == null ? 0L : virtualKeyId)
                .toolOrModel(toolOrModel == null ? "" : toolOrModel)
                .channelId(channelId == null ? "" : channelId)
                .callCount(1L)
                .failCount(success ? 0L : 1L)
                .totalDurationMs(durationMs)
                .tokenSum(tokens)
                .build();
    }
}

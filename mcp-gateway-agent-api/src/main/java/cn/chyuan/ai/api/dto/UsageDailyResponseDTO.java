package cn.chyuan.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 用量日聚合响应（工单 0046；明细行与按日汇总行同构，汇总行维度列置空）
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageDailyResponseDTO implements Serializable {

    /** yyyy-MM-dd */
    private String statDate;

    private Long virtualKeyId;

    private String toolOrModel;

    private String channelId;

    private Long callCount;

    private Long failCount;

    private Long totalDurationMs;

    private Long tokenSum;
}

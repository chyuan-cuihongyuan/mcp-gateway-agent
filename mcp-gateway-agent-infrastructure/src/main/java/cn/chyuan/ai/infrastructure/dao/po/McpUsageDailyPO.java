package cn.chyuan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 用量日聚合表（工单 0046；唯一键 stat_date×virtual_key_id×tool_or_model×channel_id，
 * 仪表盘直查聚合行避免扫明细大表——LiteLLM Daily* 口径）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpUsageDailyPO implements Serializable {

    private Long id;

    /** 聚合日期（DATE） */
    private String statDate;

    private Long virtualKeyId;

    private String toolOrModel;

    private String channelId;

    private Long callCount;

    private Long failCount;

    private Long totalDurationMs;

    private Long tokenSum;

    private java.util.Date updatedAt;
}

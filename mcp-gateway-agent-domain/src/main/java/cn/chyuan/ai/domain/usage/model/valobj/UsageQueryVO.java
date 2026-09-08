package cn.chyuan.ai.domain.usage.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用量明细查询条件（工单 0046，admin API 口径）
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageQueryVO {

    /** 起始日期（含，yyyy-MM-dd；null=不限） */
    private String fromDate;

    /** 截止日期（含，yyyy-MM-dd；null=不限） */
    private String toDate;

    private Long virtualKeyId;

    /** 工具或模型（前缀 like） */
    private String toolOrModel;

    private String status;

    private String trafficType;

    private String channelId;
}

package cn.chyuan.ai.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 按标签日聚合行（工单 0088：/admin/v1/usage/tags/daily）
 *
 * @author chyuan
 */
@Data
public class UsageTagDailyDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String statDate;

    private Long callCount;

    private Long failCount;

    /** 区间成本合计（未计价行为 null 不计入） */
    private BigDecimal costSum;
}

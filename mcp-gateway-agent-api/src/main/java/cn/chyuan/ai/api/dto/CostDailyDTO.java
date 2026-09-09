package cn.chyuan.ai.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 成本日趋势行（工单 0089）
 *
 * @author chyuan
 */
@Data
public class CostDailyDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String statDate;

    private Long callCount;

    /** 当日成本合计（未计价行不计入） */
    private BigDecimal costSum;
}

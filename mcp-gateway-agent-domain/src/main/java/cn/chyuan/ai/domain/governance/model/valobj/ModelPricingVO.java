package cn.chyuan.ai.domain.governance.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 模型计价 VO（工单 0085）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelPricingVO {

    private Long id;

    /** 模型名（唯一） */
    private String model;

    /** 输入每百万 token 单价 */
    private BigDecimal inputCostPerM;

    /** 输出每百万 token 单价 */
    private BigDecimal outputCostPerM;

    /** 币种（默认 CNY） */
    private String currency;

    /** 0-停用 1-启用（停用按未定价处理） */
    private Integer enabled;

    private String remark;

    private Date createTime;

    private Date updateTime;

    /**
     * 按 token 计价（工单 0086 消费）：输入/输出分别计价求和，6 位小数舍入。
     * 未定价（null 字段按 0 处理）。
     */
    public BigDecimal costOf(Long promptTokens, Long completionTokens) {
        long in = promptTokens == null ? 0L : promptTokens;
        long out = completionTokens == null ? 0L : completionTokens;
        BigDecimal inputCost = inputCostPerM == null ? BigDecimal.ZERO : inputCostPerM;
        BigDecimal outputCost = outputCostPerM == null ? BigDecimal.ZERO : outputCostPerM;
        return inputCost.multiply(BigDecimal.valueOf(in))
                .add(outputCost.multiply(BigDecimal.valueOf(out)))
                .divide(BigDecimal.valueOf(1_000_000), 6, java.math.RoundingMode.HALF_UP);
    }
}

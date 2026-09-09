package cn.chyuan.ai.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 模型计价请求（工单 0085）
 *
 * @author chyuan
 */
@Data
public class ModelPricingRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String model;

    private BigDecimal inputCostPerM;

    private BigDecimal outputCostPerM;

    /** 默认 CNY */
    private String currency;

    /** 默认 1 启用 */
    private Integer enabled;

    private String remark;
}

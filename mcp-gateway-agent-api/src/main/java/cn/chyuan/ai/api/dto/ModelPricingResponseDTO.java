package cn.chyuan.ai.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 模型计价响应（工单 0085）
 *
 * @author chyuan
 */
@Data
public class ModelPricingResponseDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String model;

    private BigDecimal inputCostPerM;

    private BigDecimal outputCostPerM;

    private String currency;

    private Integer enabled;

    private String remark;

    private String updateTime;
}

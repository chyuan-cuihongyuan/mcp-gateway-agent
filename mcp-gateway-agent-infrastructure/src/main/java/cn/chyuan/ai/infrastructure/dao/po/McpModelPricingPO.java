package cn.chyuan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 模型计价 PO（工单 0085：每百万 token 单价，LiteLLM cost map 口径裁剪）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpModelPricingPO {

    private Long id;

    /** 模型名（客户端可见名，唯一） */
    private String model;

    /** 输入每百万 token 单价 */
    private BigDecimal inputCostPerM;

    /** 输出每百万 token 单价 */
    private BigDecimal outputCostPerM;

    /** 币种（默认 CNY） */
    private String currency;

    /** 0-停用（按未定价处理） 1-启用 */
    private Integer enabled;

    private String remark;

    private Date createTime;

    private Date updateTime;
}

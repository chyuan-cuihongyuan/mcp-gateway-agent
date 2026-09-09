package cn.chyuan.ai.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 成本 TopN 行（工单 0089：模型/密钥/标签维度）
 *
 * @author chyuan
 */
@Data
public class CostTopNDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 维度值（模型名 / 标签 / 密钥 ID） */
    private String dim;

    private Long callCount;

    private BigDecimal costSum;
}

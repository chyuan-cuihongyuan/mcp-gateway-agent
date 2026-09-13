package cn.chyuan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * 策略定义表 PO（工单 0261 AH2）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpPolicyDefinitionPO implements Serializable {

    private Long id;

    private String name;

    private String subPattern;

    private String objPattern;

    private String actPattern;

    /** 条件表达式（可空；ExprKernel 语法） */
    private String conditionExpr;

    private String effect;

    private Integer priority;

    private Boolean enabled;

    private String note;

    private String operator;

    private Date createTime;

    private Date updateTime;
}

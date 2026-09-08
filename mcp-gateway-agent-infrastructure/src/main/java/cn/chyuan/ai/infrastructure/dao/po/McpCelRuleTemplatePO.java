package cn.chyuan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * CEL 规则模板表 PO（工单 0057）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpCelRuleTemplatePO implements Serializable {

    private Long id;

    private String code;

    private String name;

    private String expression;

    private String variablesDesc;

    /** 1-内置（不可删改），0-用户自建 */
    private Integer builtin;

    private Date createTime;

    private Date updateTime;
}

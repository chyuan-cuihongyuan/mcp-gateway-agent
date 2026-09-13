package cn.chyuan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * 策略决策日志表 PO（工单 0265 AH5）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpPolicyDecisionLogPO implements Serializable {

    private Long id;

    private Long atMs;

    /** 输入摘要（脱敏截断） */
    private String subject;

    private String object;

    private String action;

    private String decision;

    /** 命中策略名（逗号拼接） */
    private String hitStatementNames;

    private Boolean cached;

    private Long costMs;

    private Date createTime;

    private Date updateTime;
}

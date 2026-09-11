package cn.chyuan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * tag 路由规则 PO（工单 0160）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpRoutingRulePO implements Serializable {

    private Long id;

    private String ruleName;

    private String tagKey;

    private String tagValue;

    private String channelGroupId;

    private Integer priority;

    /** ACTIVE / DISABLED */
    private String status;

    private Date createTime;

    private Date updateTime;
}

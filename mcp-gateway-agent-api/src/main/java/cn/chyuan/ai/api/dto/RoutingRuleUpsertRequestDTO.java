package cn.chyuan.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * tag 路由规则创建/更新请求（工单 0160）
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoutingRuleUpsertRequestDTO implements Serializable {

    private String ruleName;

    private String tagKey;

    private String tagValue;

    /** 命中后调度的渠道组 */
    private String channelGroupId;

    /** 优先级（大者优先；默认 0） */
    private Integer priority;

    /** ACTIVE / DISABLED（默认 ACTIVE） */
    private String status;
}

package cn.chyuan.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * tag 路由规则响应（工单 0160）
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoutingRuleResponseDTO implements Serializable {

    private Long id;

    private String ruleName;

    private String tagKey;

    private String tagValue;

    private String channelGroupId;

    private Integer priority;

    private String status;

    private String createTime;

    private String updateTime;
}

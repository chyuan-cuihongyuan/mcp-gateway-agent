package cn.chyuan.ai.domain.llmchannel.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * tag 路由规则值对象（工单 0160）
 *
 * <p>语义：请求 tags 中出现 "tagKey=tagValue" 标签 → 调度限定到 channelGroupId 渠道组。
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoutingRuleVO {

    /** 启用 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** 停用 */
    public static final String STATUS_DISABLED = "DISABLED";

    private Long id;

    /** 规则名（唯一可读） */
    private String ruleName;

    /** 标签键 */
    private String tagKey;

    /** 标签值 */
    private String tagValue;

    /** 命中后调度的渠道组（mcp_llm_channel.channel_group） */
    private String channelGroupId;

    /** 优先级（多规则命中取最高，大者优先） */
    private Integer priority;

    /** ACTIVE / DISABLED */
    private String status;

    private Date createTime;

    private Date updateTime;
}

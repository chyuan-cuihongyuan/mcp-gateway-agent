package cn.chyuan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * 治理告警 webhook 端点表 PO（工单 0051）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpWebhookEndpointPO implements Serializable {

    private Long id;

    private String name;

    private String url;

    /** 订阅事件类型 JSON 数组（空=全部） */
    private String events;

    /** GENERIC/DINGTALK/FEISHU/SLACK（工单 0116） */
    private String format;

    private String secret;

    /** 0-禁用，1-启用 */
    private Integer enabled;

    private Date createTime;

    private Date updateTime;
}

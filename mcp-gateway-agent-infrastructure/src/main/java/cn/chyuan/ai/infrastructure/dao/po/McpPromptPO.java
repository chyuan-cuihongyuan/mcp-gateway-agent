package cn.chyuan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * 网关本地 Prompt 表 PO（工单 0053）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpPromptPO implements Serializable {

    private Long id;

    private String gatewayId;

    private String name;

    private String description;

    private String argumentsJson;

    private String template;

    private Integer status;

    private Date createTime;

    private Date updateTime;
}

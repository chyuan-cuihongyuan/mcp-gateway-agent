package cn.chyuan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * 网关本地 Resource 表 PO（工单 0053）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpResourcePO implements Serializable {

    private Long id;

    private String gatewayId;

    private String uri;

    private String name;

    private String description;

    private String mimeType;

    private String content;

    private Integer status;

    private Date createTime;

    private Date updateTime;
}

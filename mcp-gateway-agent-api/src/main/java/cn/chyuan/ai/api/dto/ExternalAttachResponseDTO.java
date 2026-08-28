package cn.chyuan.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * 外部 MCP 挂接响应（工单 0021；凭证脱敏）
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExternalAttachResponseDTO implements Serializable {

    private Long id;

    private String gatewayId;

    private String attachName;

    private String transportType;

    private String endpoint;

    /** 已配置返回 "****"，未配置返回 null */
    private String apiKeyMasked;

    private String command;

    private String args;

    private String env;

    private Integer requestTimeoutMs;

    private Integer status;

    /** UNKNOWN / CONNECTED / FAILED */
    private String connectStatus;

    private String connectError;

    private Date connectTime;

    /** 运行期缓存的工具数（未连接为 0） */
    private Integer toolCount;

    private Date createTime;

    private Date updateTime;
}

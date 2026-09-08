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

    /** 同层加权（工单 0047） */
    private Integer weight;

    /** 调度优先级分层（工单 0047） */
    private Integer priority;

    /** 最近一次探测时间（工单 0047） */
    private Date testTime;

    /** 最近一次探测耗时毫秒（工单 0047） */
    private Long responseTimeMs;

    /** 冷却截止时间（工单 0047） */
    private Date cooldownUntil;

    /** 上游鉴权类型（工单 0047） */
    private String authType;

    /** 已配置返回 "****"，未配置返回 null（auth_config 脱敏） */
    private String authConfigMasked;

    /** UNKNOWN / CONNECTED / FAILED */
    private String connectStatus;

    private String connectError;

    private Date connectTime;

    /** 运行期缓存的工具数（未连接为 0） */
    private Integer toolCount;

    private Date createTime;

    private Date updateTime;
}

package cn.chyuan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * 外部 MCP 挂接配置表 PO（工单 0021）
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpExternalAttachPO implements Serializable {

    private Long id;

    /** 挂接归属网关 */
    private String gatewayId;

    /** 挂接名（外部工具前缀） */
    private String attachName;

    /** STREAMABLE_HTTP / STDIO */
    private String transportType;

    /** STREAMABLE_HTTP：上游 MCP 端点完整 URL */
    private String endpoint;

    /** STREAMABLE_HTTP：Bearer 凭证 */
    private String apiKey;

    /** STDIO：可执行命令 */
    private String command;

    /** STDIO：命令参数（JSON 数组字符串） */
    private String args;

    /** STDIO：环境变量（JSON 对象字符串） */
    private String env;

    /** 上游请求超时（毫秒） */
    private Integer requestTimeoutMs;

    /** 0-禁用，1-启用 */
    private Integer status;

    /** UNKNOWN / CONNECTED / FAILED */
    private String connectStatus;

    private String connectError;

    private Date connectTime;

    private Date createTime;

    private Date updateTime;
}

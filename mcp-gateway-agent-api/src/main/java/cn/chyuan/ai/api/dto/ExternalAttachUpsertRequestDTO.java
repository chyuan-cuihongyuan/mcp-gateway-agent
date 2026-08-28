package cn.chyuan.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 外部 MCP 挂接创建/更新请求（工单 0021）
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExternalAttachUpsertRequestDTO implements Serializable {

    private String gatewayId;

    /** 挂接名（工具前缀），创建后不可改 */
    private String attachName;

    /** STREAMABLE_HTTP / STDIO（SSE 挂接已随 0021 下线） */
    private String transportType;

    /** STREAMABLE_HTTP：上游端点完整 URL */
    private String endpoint;

    /** STREAMABLE_HTTP：Bearer 凭证（可选） */
    private String apiKey;

    /** STDIO：可执行命令 */
    private String command;

    /** STDIO：命令参数（JSON 数组字符串） */
    private String args;

    /** STDIO：环境变量（JSON 对象字符串） */
    private String env;

    /** 上游请求超时毫秒（默认 30000） */
    private Integer requestTimeoutMs;

    /** 0-禁用，1-启用（默认 1） */
    private Integer status;
}

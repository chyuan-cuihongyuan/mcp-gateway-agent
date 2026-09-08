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

    /** 0-手动禁用，1-启用（默认 1）；2-自动禁用不可经此设置 */
    private Integer status;

    /** 同层加权（默认 1，>=1） */
    private Integer weight;

    /** 调度优先级分层（默认 0，>=0，大者先选） */
    private Integer priority;

    /** 上游鉴权类型 NONE/HEADER/BEARER/OAUTH_CC（默认按 apiKey 有无推导，0061 起全量生效） */
    private String authType;

    /** 上游鉴权配置 JSON（HEADER: {"name","value"}；OAUTH_CC: {"tokenUrl","clientId","clientSecret","scope"}） */
    private String authConfig;
}

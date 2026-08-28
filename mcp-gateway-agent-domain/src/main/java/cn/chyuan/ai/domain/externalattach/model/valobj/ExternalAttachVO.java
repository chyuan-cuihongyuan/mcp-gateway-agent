package cn.chyuan.ai.domain.externalattach.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 外部 MCP 挂接配置值对象（工单 0021）
 *
 * <p>挂接名即外部工具前缀：上游工具 {@code foo} 挂接为 {@code ext} 后，
 * 在网关工具清单中呈现为 {@code ext_foo}（命名规则承接
 * {@code JavaSDKMCPClient_getCompanyEmployee} 既有约定）。
 *
 * @author chyuan
 */
@Data
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
public class ExternalAttachVO {

    /** 传输类型：Streamable HTTP 客户端挂接 */
    public static final String TRANSPORT_STREAMABLE_HTTP = "STREAMABLE_HTTP";

    /** 传输类型：stdio 客户端挂接 */
    public static final String TRANSPORT_STDIO = "STDIO";

    private Long id;

    /** 挂接归属网关 */
    private String gatewayId;

    /** 挂接名（工具前缀），^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$ */
    private String attachName;

    /** STREAMABLE_HTTP / STDIO */
    private String transportType;

    /** STREAMABLE_HTTP：上游 MCP 端点完整 URL */
    private String endpoint;

    /** STREAMABLE_HTTP：Bearer 凭证（可选；上游为另一网关时填其虚拟密钥） */
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

    /** UNKNOWN / CONNECTED / FAILED（运行期回写） */
    private String connectStatus;

    /** 最近一次连接失败原因 */
    private String connectError;

    /** 最近一次连接状态变更时间 */
    private Date connectTime;

    private Date createTime;

    private Date updateTime;

}

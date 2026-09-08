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

    /** 渠道状态：手动禁用（工单 0047 三态） */
    public static final int STATUS_MANUAL_DISABLED = 0;

    /** 渠道状态：启用 */
    public static final int STATUS_ENABLED = 1;

    /** 渠道状态：自动禁用（巡检连击/被动熔断置位，探测成功恢复；admin 不可直接设置） */
    public static final int STATUS_AUTO_DISABLED = 2;

    /** 上游鉴权类型（工单 0047 落模型，生效于 0061） */
    public static final String AUTH_TYPE_NONE = "NONE";
    public static final String AUTH_TYPE_HEADER = "HEADER";
    public static final String AUTH_TYPE_BEARER = "BEARER";
    public static final String AUTH_TYPE_OAUTH_CC = "OAUTH_CC";

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

    /** 0-手动禁用，1-启用，2-自动禁用（工单 0047 渠道三态） */
    private Integer status;

    /** 同层加权（调度用，工单 0047；默认 1） */
    private Integer weight;

    /** 调度优先级分层（大者先选，工单 0047；默认 0） */
    private Integer priority;

    /** 最近一次探测时间（工单 0047） */
    private Date testTime;

    /** 最近一次探测耗时毫秒（工单 0047） */
    private Long responseTimeMs;

    /** 冷却截止时间（自动禁用后 cool 秒内不被调度选中，工单 0047） */
    private Date cooldownUntil;

    /** 被动熔断计数：连接失败（工单 0047 落列，0059 生效） */
    private Integer failConnect;

    /** 被动熔断计数：超时（工单 0047 落列，0059 生效） */
    private Integer failTimeout;

    /** 被动熔断计数：HTTP 5xx（工单 0047 落列，0059 生效） */
    private Integer failHttp;

    /** 上游鉴权类型 NONE/HEADER/BEARER/OAUTH_CC（工单 0047；apiKey 字段为存量等价 HEADER-Bearer 形态） */
    private String authType;

    /** 上游鉴权配置 JSON（工单 0047 落列，0061 生效；0062 起密文存储） */
    private String authConfig;

    /** UNKNOWN / CONNECTED / FAILED（运行期回写） */
    private String connectStatus;

    /** 最近一次连接失败原因 */
    private String connectError;

    /** 最近一次连接状态变更时间 */
    private Date connectTime;

    private Date createTime;

    private Date updateTime;

}

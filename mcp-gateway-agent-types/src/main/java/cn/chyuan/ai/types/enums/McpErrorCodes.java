package cn.chyuan.ai.types.enums;

/**
 * MCP错误代码常量定义
 * 符合JSON-RPC 2.0规范
 */
public final class McpErrorCodes {

    // JSON-RPC 2.0 标准错误代码

    /**
     * 解析错误 - 无效的JSON
     */
    public static final int PARSE_ERROR = -32700;

    /**
     * 无效请求 - JSON不是有效的请求对象
     */
    public static final int INVALID_REQUEST = -32600;

    /**
     * 方法未找到 - 方法不存在或不可用
     */
    public static final int METHOD_NOT_FOUND = -32601;

    /**
     * 无效参数 - 无效的方法参数
     */
    public static final int INVALID_PARAMS = -32602;

    /**
     * 内部错误 - 内部JSON-RPC错误
     */
    public static final int INTERNAL_ERROR = -32603;

    // MCP特定错误代码（-32000到-32099为服务器实现定义的错误）

    /**
     * 会话未找到
     */
    public static final int SESSION_NOT_FOUND = -32000;

    /**
     * 会话已过期
     */
    public static final int SESSION_EXPIRED = -32001;

    /**
     * 服务器正在关闭
     */
    public static final int SERVER_SHUTTING_DOWN = -32002;

    /**
     * 工具未找到
     */
    public static final int TOOL_NOT_FOUND = -32003;

    /**
     * 工具执行失败
     */
    public static final int TOOL_EXECUTION_FAILED = -32004;

    /**
     * 资源未找到
     */
    public static final int RESOURCE_NOT_FOUND = -32005;

    /**
     * 权限不足
     */
    public static final int INSUFFICIENT_PERMISSIONS = -32006;

    /**
     * 协议版本不支持
     */
    public static final int UNSUPPORTED_PROTOCOL_VERSION = -32007;

    /**
     * 未认证 —— 缺少调用凭证（治理面统一认证，HTTP 401）
     */
    public static final int AUTH_REQUIRED = -32008;

    /**
     * 超出配额 —— per-key RPM/日配额耗尽（治理面配额限流，HTTP 429，含剩余额度信息）
     */
    public static final int QUOTA_EXCEEDED = -32009;

    /**
     * 配额服务不可用 —— Redis 故障，按 0011 决议 fail-closed 拒绝（HTTP 503）
     */
    public static final int QUOTA_SERVICE_UNAVAILABLE = -32010;

    /**
     * 凭证已过期 —— 密钥生命周期四态之一（工单 0045，HTTP 403）
     */
    public static final int KEY_EXPIRED = -32011;

    /**
     * 凭证已禁用/吊销 —— 密钥生命周期四态之一（工单 0045，HTTP 403）
     */
    public static final int KEY_DISABLED = -32012;

    /**
     * 来源 IP 不在密钥白名单 —— IP/CIDR 限制（工单 0045，HTTP 403）
     */
    public static final int IP_NOT_ALLOWED = -32013;

    /**
     * 预算耗尽 —— 密钥周期窗口硬预算上限（工单 0050，HTTP 429，与日配额 -32009 语义区分）
     */
    public static final int BUDGET_EXCEEDED = -32014;

    /**
     * 请求体超限 —— 大小上限防护（工单 0056，HTTP 413，含限制值与实际值）
     */
    public static final int REQUEST_TOO_LARGE = -32015;

    /**
     * 并发超限 —— 每密钥并发上限（工单 0056，HTTP 429）
     */
    public static final int CONCURRENCY_EXCEEDED = -32016;

    /**
     * 金额预算耗尽 —— 密钥窗口成本硬线（工单 0087，HTTP 429，与次数口径 -32014 语义区分）
     */
    public static final int COST_LIMIT_EXCEEDED = -32017;

    /**
     * 内容命中安全护栏 —— 关键词/正则拦截（工单 0091；JSON-RPC 面协议错误、/v1 面 OpenAI error）
     */
    public static final int CONTENT_BLOCKED = -32018;

    /**
     * 渠道组容量耗尽 —— 可用 weight 占比低于阈值（工单 0106，HTTP 503 语义）
     */
    public static final int GROUP_CAPACITY_EXHAUSTED = -32019;

    /**
     * 渠道请求大小超限 —— 渠道级 max_body_bytes 请求体预算（工单 0156，HTTP 413 语义；
     * 与全局 -32015 语义区分：该码为渠道维度出站预算，全局码为网关入站上限）
     */
    public static final int CHANNEL_BODY_TOO_LARGE = -32020;

    /**
     * 模型不在密钥白名单 —— vk 级 allowed_models 授权细化（工单 0157，HTTP 403 语义；
     * 与 CEL 治理 -32006 语义区分：该码为密钥静态授权，CEL 为规则动态治理）
     */
    public static final int KEY_MODEL_NOT_ALLOWED = -32021;

    /**
     * 渠道并发超限 —— 渠道级 max_concurrency 排队超时（工单 0161，HTTP 429 语义；
     * 与每密钥并发 -32016 语义区分：该码为渠道维度上游保护）
     */
    public static final int CHANNEL_CONCURRENCY_EXCEEDED = -32022;

    /**
     * 上下文超限 —— 估算 prompt+max_tokens 超出渠道 context 上限（工单 0162，HTTP 400 语义；
     * 提前拒绝省下注定失败的调用费）
     */
    public static final int MODEL_CONTEXT_EXCEEDED = -32023;

    private McpErrorCodes() {
        // 工具类，禁止实例化
    }
}

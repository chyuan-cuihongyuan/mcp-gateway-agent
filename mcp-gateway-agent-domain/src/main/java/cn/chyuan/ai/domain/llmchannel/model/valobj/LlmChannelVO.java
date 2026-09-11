package cn.chyuan.ai.domain.llmchannel.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * LLM 渠道值对象（工单 0063，one-api Channel 口径裁剪）
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmChannelVO {

    /** 渠道状态：手动禁用 */
    public static final int STATUS_MANUAL_DISABLED = 0;

    /** 渠道状态：启用 */
    public static final int STATUS_ENABLED = 1;

    /** 渠道状态：自动禁用（探测失败连击/预留，恢复探测） */
    public static final int STATUS_AUTO_DISABLED = 2;

    private Long id;

    /** 渠道名（唯一可读） */
    private String name;

    /** 上游 base URL（如 https://api.deepseek.com） */
    private String baseUrl;

    /** 上游凭证（Bearer；密文落库 0062 口径，VO 内为明文运行态） */
    private String credential;

    /** 供给模型清单（逗号分隔，客户端可见名） */
    private String models;

    /** 模型名映射 JSON（客户端名→上游名，如 {"gpt-4o":"deepseek-v4-pro"}） */
    private String modelMapping;

    /** 同层加权（默认 1） */
    private Integer weight;

    /** 调度优先级（默认 0） */
    private Integer priority;

    /** 渠道组（工单 0160 tag 路由限定；空=默认组 default） */
    private String channelGroup;

    /** 0/1/2 三态 */
    private Integer status;

    /** 上游请求超时毫秒（默认 60000） */
    private Integer timeoutMs;

    /** 渠道请求体预算字节（工单 0156；空=不限，出站前置校验超限 -32020 拒绝） */
    private Long maxBodyBytes;
    /** 重试次数上限（工单 0105；0/空=不重试，≤3） */
    private Integer numRetries;
    /** 重试退避基值毫秒（指数 base*2^n） */
    private Integer retryBackoffMs;
    /** 重试错误类型（逗号分隔 429/5xx/timeout；空=不重试） */
    private String retryOn;

    /** fallback 渠道 id（工单 0155：本渠道重试耗尽后沿链降级；空=无降级，保存时防环校验） */
    private Long fallbackChannelId;

    /** 余额探测（工单 0108） */
    private String balanceProbeUrl;
    private String balanceJsonPath;
    private String balance;
    private java.util.Date balanceTime;

    /** 最近探测时间/耗时 */
    private Date testTime;

    private Long responseTimeMs;

    private Date createTime;

    private Date updateTime;
}

package cn.chyuan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * LLM 渠道表 PO（工单 0063，one-api Channel 口径）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpLlmChannelPO implements Serializable {

    private Long id;

    private String name;

    private String baseUrl;

    /** Bearer 凭证（密文存储，0062） */
    private String credential;

    /** 供给模型（逗号分隔） */
    private String models;

    /** 模型名映射 JSON（客户端名→上游名） */
    private String modelMapping;

    private Integer weight;

    private Integer priority;

    /** 0-手动禁用 1-启用 2-自动禁用 */
    private Integer status;

    private Integer timeoutMs;

    /** 渠道请求体预算字节（工单 0156；空=不限） */
    private Long maxBodyBytes;
    /** 重试次数上限（工单 0105；0/空=不重试，≤3） */
    private Integer numRetries;
    /** 重试退避基值毫秒（指数 base*2^n） */
    private Integer retryBackoffMs;
    /** 重试错误类型（逗号分隔 429/5xx/timeout；空=不重试） */
    private String retryOn;

    /** fallback 渠道 id（工单 0155；空=无降级） */
    private Long fallbackChannelId;

    /** 余额探测（工单 0108）：查询 URL + JSON 路径（可空=不探测） */
    private String balanceProbeUrl;
    private String balanceJsonPath;
    /** 最近一次余额与时间（探测成功落列） */
    private String balance;
    private java.util.Date balanceTime;

    private Date testTime;

    private Long responseTimeMs;

    private Date createTime;

    private Date updateTime;
}

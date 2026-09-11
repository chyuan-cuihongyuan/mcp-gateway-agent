package cn.chyuan.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * LLM 渠道创建/更新请求（工单 0063；credential 更新时空值=保留原值）
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmChannelRequestDTO implements Serializable {

    private String name;

    private String baseUrl;

    /** Bearer 凭证（响应恒脱敏） */
    private String credential;

    /** 供给模型（逗号分隔） */
    private String models;

    /** 模型名映射 JSON（客户端名→上游名） */
    private String modelMapping;

    private Integer weight;

    private Integer priority;

    /** 0-禁用 1-启用（默认 1） */
    private Integer status;

    private Integer timeoutMs;

    /** 渠道请求体预算字节（工单 0156；空=不限） */
    private Long maxBodyBytes;

    /** 重试策略（工单 0105）：次数上限（≤3）/退避基值毫秒/retry_on（429,5xx,timeout） */
    private Integer numRetries;
    private Integer retryBackoffMs;
    private String retryOn;

    /** fallback 渠道 id（工单 0155：本渠道重试耗尽后沿链降级；空=无降级） */
    private Long fallbackChannelId;

    /** 余额探测（工单 0108） */
    private String balanceProbeUrl;
    private String balanceJsonPath;
    private String balance;
    private String balanceTime;
}

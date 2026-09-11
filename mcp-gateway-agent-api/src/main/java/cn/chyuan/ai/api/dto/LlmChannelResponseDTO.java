package cn.chyuan.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * LLM 渠道响应（工单 0063；credential 恒脱敏）
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmChannelResponseDTO implements Serializable {

    private Long id;

    private String name;

    private String baseUrl;

    /** 已配置返回 "****" */
    private String credentialMasked;

    private String models;

    private String modelMapping;

    private Integer weight;

    private Integer priority;

    /** 渠道组（工单 0160 tag 路由限定；空=默认组 default） */
    private String channelGroup;

    private Integer status;

    private Integer timeoutMs;

    /** 渠道请求体预算字节（工单 0156；空=不限） */
    private Long maxBodyBytes;

    /** 渠道并发上限（工单 0161；空/0=不限制） */
    private Integer maxConcurrency;

    private String testTime;

    private Long responseTimeMs;

    /** 重试策略（工单 0105）：次数上限（≤3）/退避基值毫秒/retry_on（429,5xx,timeout） */
    private Integer numRetries;
    private Integer retryBackoffMs;
    private String retryOn;

    /** fallback 渠道 id（工单 0155：空=无降级） */
    private Long fallbackChannelId;

    /** 余额探测（工单 0108） */
    private String balanceProbeUrl;
    private String balanceJsonPath;
    private String balance;
    private String balanceTime;
}

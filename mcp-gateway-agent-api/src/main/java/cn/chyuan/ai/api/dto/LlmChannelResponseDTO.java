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

    private Integer status;

    private Integer timeoutMs;

    private String testTime;

    private Long responseTimeMs;
}

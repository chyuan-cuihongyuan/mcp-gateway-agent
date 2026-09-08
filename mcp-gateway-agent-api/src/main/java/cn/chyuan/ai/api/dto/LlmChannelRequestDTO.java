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
}

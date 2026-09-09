package cn.chyuan.ai.api.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 治理护栏请求/响应（工单 0091）
 *
 * @author chyuan
 */
@Data
public class GuardrailDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 唯一名 */
    private String name;

    /** PII_MASK / KEYWORD_BLOCK / REGEX_BLOCK / RESPONSE_FILTER / RESPONSE_MASK */
    private String type;

    /** PRE_CALL / POST_CALL / LOGGING_ONLY */
    private String mode;

    /** 配置 JSON（PII 单类开关 / keywords 数组 / patterns 数组） */
    private String config;

    /** MCP / LLM / ALL */
    private String trafficMask;

    /** 执行顺序（小者先） */
    private Integer priority;

    /** 0-停用 1-启用 */
    private Integer enabled;

    private String updateTime;
}

package cn.chyuan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 治理护栏 PO（工单 0091：内容安全执行链）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpGuardrailPO {

    private Long id;

    /** 护栏名（唯一） */
    private String name;

    /** PII_MASK / KEYWORD_BLOCK / REGEX_BLOCK / RESPONSE_FILTER / RESPONSE_MASK */
    private String type;

    /** PRE_CALL / POST_CALL / LOGGING_ONLY */
    private String mode;

    /** 配置 JSON（类型相关：PII 单类开关 / 关键词数组 / 正则数组） */
    private String config;

    /** MCP / LLM / ALL */
    private String trafficMask;

    /** 执行顺序（小者先） */
    private Integer priority;

    /** 0-停用 1-启用 */
    private Integer enabled;

    private Date createTime;

    private Date updateTime;
}

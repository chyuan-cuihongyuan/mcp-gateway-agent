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

    private Date testTime;

    private Long responseTimeMs;

    private Date createTime;

    private Date updateTime;
}

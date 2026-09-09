package cn.chyuan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * 用量明细表（工单 0046，LiteLLM SpendLogs 字段面裁剪；密钥存哈希不存明文）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpUsageLogPO implements Serializable {

    private Long id;

    /** 请求唯一标识 */
    private String requestId;

    /** 虚拟密钥 ID（未知/匿名哨兵 0） */
    private Long virtualKeyId;

    /** 凭证 SHA-256 哈希 */
    private String apiKeyHash;

    private String gatewayId;

    /** MCP / LLM / A2A */
    private String trafficType;

    /** 工具名（MCP）或模型名（LLM） */
    private String toolOrModel;

    /** 上游渠道（本地哨兵 ''） */
    private String channelId;

    /** SUCCESS / FAIL */
    private String status;

    private Integer durationMs;

    private Long promptTokens;

    private Long completionTokens;

    /** 本次调用成本（工单 0086：未定价为 null） */
    private java.math.BigDecimal cost;

    /** 请求标签（工单 0088：规范形 ",a,b,"） */
    private String tags;

    private String clientIp;

    private String sessionId;

    private Date createdAt;
}

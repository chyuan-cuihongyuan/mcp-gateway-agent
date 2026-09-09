package cn.chyuan.ai.domain.usage.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 用量明细记录值对象（工单 0046，LiteLLM SpendLogs 字段面对齐裁剪）
 *
 * <p>密钥只落哈希不落明文；token 两列 LLM 流量填、MCP 流量为 null。
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageRecordVO {

    /** 请求唯一标识（网关生成 UUID） */
    private String requestId;

    /** 命中的虚拟密钥 ID（匿名/未知为 null，落库哨兵 0） */
    private Long virtualKeyId;

    /** 凭证 SHA-256 哈希（脱敏口径） */
    private String apiKeyHash;

    private String gatewayId;

    /** MCP / LLM / A2A */
    private String trafficType;

    /** 工具名（MCP）或模型名（LLM）；list/initialize 等方法名原样记录 */
    private String toolOrModel;

    /** 上游渠道（外部挂接名/LLM 渠道名；本地为 null，落库哨兵 ''） */
    private String channelId;

    /** SUCCESS / FAIL */
    private String status;

    private Integer durationMs;

    private Long promptTokens;

    private Long completionTokens;

    /** 本次调用成本（工单 0086：LLM 面按计价表计算；未定价为 null——MCP 面恒 null） */
    private java.math.BigDecimal cost;

    /** 请求标签（工单 0088：规范形 ",a,b,"——TagParser.toStorage） */
    private String tags;

    /** 缓存命中（工单 0099：1=命中；0/null=未走缓存） */
    private Integer cacheHit;

    private String clientIp;

    private String sessionId;

    private Date createdAt;
}

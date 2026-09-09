package cn.chyuan.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 用量明细响应（工单 0046；密钥脱敏只携哈希摘要位）
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageLogResponseDTO implements Serializable {

    private String requestId;

    private Long virtualKeyId;

    /** 凭证哈希摘要（前 8 位，脱敏） */
    private String apiKeyHashPrefix;

    private String gatewayId;

    private String trafficType;

    private String toolOrModel;

    private String channelId;

    private String status;

    private Integer durationMs;

    private Long promptTokens;

    private Long completionTokens;

    /** 本次调用成本（工单 0086：未定价为 null） */
    private java.math.BigDecimal cost;

    private String clientIp;

    private String sessionId;

    private String createdAt;
}

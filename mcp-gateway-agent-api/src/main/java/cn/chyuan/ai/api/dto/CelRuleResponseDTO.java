package cn.chyuan.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CEL 规则响应（工单 0018）
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CelRuleResponseDTO {

    private Long id;

    private String ruleName;

    private String expression;

    /** GLOBAL / GATEWAY / VIRTUAL_KEY */
    private String scopeType;

    private String gatewayId;

    private Long virtualKeyId;

    /** ACTIVE / DISABLED */
    private String status;

    private String createdAt;

    private String updatedAt;
}

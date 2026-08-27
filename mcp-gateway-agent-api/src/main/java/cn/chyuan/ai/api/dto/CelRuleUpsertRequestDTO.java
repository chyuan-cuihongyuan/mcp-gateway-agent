package cn.chyuan.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CEL 规则创建/更新请求（工单 0018）
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CelRuleUpsertRequestDTO {

    /** 规则名（唯一） */
    private String ruleName;

    /** CEL 表达式（变量面见 0011 决议：auth.* / key.quota.* / mcp.*） */
    private String expression;

    /** GLOBAL / GATEWAY / VIRTUAL_KEY */
    private String scopeType;

    /** scope=GATEWAY 时的目标网关 */
    private String gatewayId;

    /** scope=VIRTUAL_KEY 时的目标密钥 */
    private Long virtualKeyId;

    /** ACTIVE / DISABLED（缺省 ACTIVE） */
    private String status;
}

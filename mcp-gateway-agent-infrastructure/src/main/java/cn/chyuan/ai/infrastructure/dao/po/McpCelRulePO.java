package cn.chyuan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * CEL 工具治理规则表（工单 0018 / 0011 决议五表之一）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpCelRulePO implements Serializable {

    private Long id;

    /** 规则名（唯一） */
    private String ruleName;

    /** CEL 表达式，求值结果须为 bool */
    private String expression;

    /** GLOBAL / GATEWAY / VIRTUAL_KEY */
    private String scopeType;

    /** scope=GATEWAY 时的目标网关 */
    private String gatewayId;

    /** scope=VIRTUAL_KEY 时的目标密钥 */
    private Long virtualKeyId;

    /** ACTIVE / DISABLED */
    private String status;

    private Date createdAt;

    private Date updatedAt;
}

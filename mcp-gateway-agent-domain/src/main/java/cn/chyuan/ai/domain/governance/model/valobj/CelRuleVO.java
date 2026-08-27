package cn.chyuan.ai.domain.governance.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * CEL 工具治理规则值对象（工单 0018 / 0011 决议：单表三层作用域）
 *
 * <p>求值语义：GLOBAL → GATEWAY → VIRTUAL_KEY 全部适用规则 AND 合并，
 * 任一规则求值为 false 即拒绝；求值异常按 fail-closed 处理。
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CelRuleVO {

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

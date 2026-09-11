package cn.chyuan.ai.domain.governance.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 虚拟密钥创建/更新指令实体（工单 0017）
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VirtualKeyCommandEntity {

    /** 更新时指定；创建时为空 */
    private Long id;

    private String keyName;

    private String ownerUserId;

    private String tenantId;

    private Date expiresAt;

    private Integer rpmLimit;

    private Integer dailyRequestLimit;

    private Integer dailyToolCallLimit;

    private Integer tpmLimit;

    private Double dailyCostLimit;

    /** IP/CIDR 白名单（空=不限，工单 0045） */
    private java.util.List<String> ipAllowList;

    /** 预算软线/硬线/窗口小时（工单 0050；均空=不启用预算） */
    private Long budgetSoft;
    private Long budgetHard;
    private Integer budgetDurationHours;

    /** 金额预算软/硬线（工单 0087；窗口与次数预算共振） */
    private java.math.BigDecimal costSoftLimit;
    private java.math.BigDecimal costHardLimit;

    /** 允许跳过内容护栏（工单 0095） */
    private Boolean skipGuardrailAllowed;

    /** 模型白名单（工单 0157：空=不限制；条目 trim 非空校验） */
    private java.util.List<String> allowedModels;
}

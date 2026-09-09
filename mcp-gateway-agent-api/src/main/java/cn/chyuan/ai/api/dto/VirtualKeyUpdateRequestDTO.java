package cn.chyuan.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 虚拟密钥更新请求（工单 0017；id 走路径参数）
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VirtualKeyUpdateRequestDTO implements Serializable {

    private String keyName;

    private String ownerUserId;

    private String tenantId;

    private String expiresAt;

    private Integer rpmLimit;

    private Integer dailyRequestLimit;

    private Integer dailyToolCallLimit;

    private Integer tpmLimit;

    private Double dailyCostLimit;

    /** IP/CIDR 白名单（空=不限，工单 0045） */
    private List<String> ipAllowList;

    /** 预算软线/硬线/窗口小时（工单 0050；hard 空=不启用） */
    private Long budgetSoft;
    private Long budgetHard;
    private Integer budgetDurationHours;

    /** 金额预算软/硬线（工单 0087） */
    private java.math.BigDecimal costSoftLimit;
    private java.math.BigDecimal costHardLimit;

    /** 允许跳过内容护栏（工单 0095；admin 设置） */
    private Boolean skipGuardrailAllowed;

    /** 追加授权的网关 ID 列表（可选） */
    private List<String> grantGatewayIds;

    /** 回收授权的网关 ID 列表（可选） */
    private List<String> revokeGatewayIds;
}

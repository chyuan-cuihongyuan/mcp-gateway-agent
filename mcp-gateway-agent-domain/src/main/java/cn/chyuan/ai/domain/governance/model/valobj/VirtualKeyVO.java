package cn.chyuan.ai.domain.governance.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 虚拟密钥值对象（脱敏形态，工单 0017 / 0011 决策③）
 *
 * <p>仅在创建（与重新生成）时以 {@link #plaintextOnce} 返回一次明文，
 * 其余场合只携带脱敏展示 {@link #maskedKey}。
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VirtualKeyVO {

    private Long id;

    /** 脱敏展示（vk-abc1****） */
    private String maskedKey;

    /** 明文凭证 —— 仅创建响应返回一次，之后为 null */
    private String plaintextOnce;

    private String keyName;

    private String ownerUserId;

    private String tenantId;

    /** ACTIVE / DISABLED / REVOKED */
    private String status;

    /** 派生状态（工单 0045 四态：ACTIVE/DISABLED/REVOKED/EXPIRED，QUOTA_EXHAUSTED 随预算票扩展）——查询时计算，不落库 */
    private String derivedStatus;

    private Date expiresAt;

    /** 最后活跃时间（认证命中去抖更新，工单 0045） */
    private Date lastActiveAt;

    /** IP/CIDR 白名单（空=不限，工单 0045） */
    private java.util.List<String> ipAllowList;

    /** 累计轮换次数（工单 0049） */
    private Integer rotationCount;

    /** 上次轮换时间（工单 0049） */
    private Date lastRotationAt;

    /** 预算软线（越过告警不阻断，工单 0050；空=不启用） */
    private Long budgetSoft;

    /** 预算硬线（阻断，工单 0050；空=不启用） */
    private Long budgetHard;

    /** 预算窗口时长（小时，工单 0050） */
    private Integer budgetDurationHours;

    /** 预算窗口重置时间（工单 0050，惰性重置） */
    private Date budgetResetAt;

    /** 预算窗口内已用次数（工单 0050） */
    private Long budgetUsed;

    private Integer rpmLimit;

    private Integer dailyRequestLimit;

    private Integer dailyToolCallLimit;

    private Integer tpmLimit;

    private Double dailyCostLimit;

    private Date createdAt;

    private Date updatedAt;
}

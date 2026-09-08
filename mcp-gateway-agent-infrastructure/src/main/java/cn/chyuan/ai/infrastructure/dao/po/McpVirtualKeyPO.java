package cn.chyuan.ai.infrastructure.dao.po;

import cn.chyuan.ai.infrastructure.dao.po.base.BasePagePO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * 虚拟密钥表（工单 0017 / 0011 决策③：SHA-256 哈希存储）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpVirtualKeyPO extends BasePagePO {

    /** 主键ID */
    private Long id;

    /** vk- 凭证的 SHA-256 十六进制哈希 */
    private String apiKeyHash;

    /** 管理员可读名称 */
    private String keyName;

    /** 绑定身份（可选） */
    private String ownerUserId;

    /** 租户标识（可选） */
    private String tenantId;

    /** ACTIVE / DISABLED / REVOKED */
    private String status;

    /** 过期时间（NULL 永不过期） */
    private Date expiresAt;

    /** 每分钟请求限额（NULL 不限） */
    private Integer rpmLimit;

    /** 日请求配额（NULL 不限） */
    private Integer dailyRequestLimit;

    /** 日工具调用配额（NULL 不限） */
    private Integer dailyToolCallLimit;

    /** TPM 预留（本期不执行，仅建列） */
    private Integer tpmLimit;

    /** 日成本配额预留（本期不执行，仅建列） */
    private Double dailyCostLimit;

    /** 最后活跃时间（认证命中去抖更新，工单 0045） */
    private Date lastActiveAt;

    /** IP/CIDR 白名单 JSON 数组字符串（空/NULL=不限，工单 0045） */
    private String ipAllowList;

    /** 累计轮换次数（工单 0049） */
    private Integer rotationCount;

    /** 上次轮换时间（工单 0049） */
    private Date lastRotationAt;

    /** 前代凭证哈希（宽限期内可认证，工单 0049） */
    private String prevKeyHash;

    /** 宽限期截止（工单 0049） */
    private Date graceUntil;

    /** 预算软线/硬线/窗口小时/重置时间/窗口已用（工单 0050） */
    private Long budgetSoft;
    private Long budgetHard;
    private Integer budgetDurationHours;
    private Date budgetResetAt;
    private Long budgetUsed;

    private Date createdAt;

    private Date updatedAt;
}

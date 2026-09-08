package cn.chyuan.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 虚拟密钥响应（脱敏；plaintextOnce 仅创建响应出现一次，工单 0017）
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VirtualKeyResponseDTO implements Serializable {

    private Long id;

    /** 脱敏凭证（vk-abc1****） */
    private String maskedKey;

    /** 明文凭证 —— 仅创建时返回一次 */
    private String plaintextOnce;

    private String keyName;

    private String ownerUserId;

    private String tenantId;

    private String status;

    /** 派生状态四态（工单 0045）：ACTIVE/DISABLED/REVOKED/EXPIRED（QUOTA_EXHAUSTED 随预算扩展） */
    private String derivedStatus;

    private String expiresAt;

    /** 最后活跃时间（工单 0045） */
    private String lastActiveAt;

    /** IP/CIDR 白名单（空=不限，工单 0045） */
    private List<String> ipAllowList;

    private Integer rpmLimit;

    private Integer dailyRequestLimit;

    private Integer dailyToolCallLimit;

    private Integer tpmLimit;

    private Double dailyCostLimit;

    private String createdAt;

    /** 已授权网关 ID 列表 */
    private List<String> grants;
}

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

    private String expiresAt;

    private Integer rpmLimit;

    private Integer dailyRequestLimit;

    private Integer dailyToolCallLimit;

    private Integer tpmLimit;

    private Double dailyCostLimit;

    private String createdAt;

    /** 已授权网关 ID 列表 */
    private List<String> grants;
}

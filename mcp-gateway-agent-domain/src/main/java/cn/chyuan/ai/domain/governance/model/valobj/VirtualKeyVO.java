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

    private Date expiresAt;

    private Integer rpmLimit;

    private Integer dailyRequestLimit;

    private Integer dailyToolCallLimit;

    private Integer tpmLimit;

    private Double dailyCostLimit;

    private Date createdAt;

    private Date updatedAt;
}

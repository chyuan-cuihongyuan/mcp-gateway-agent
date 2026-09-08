package cn.chyuan.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 虚拟密钥创建请求（工单 0017）
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VirtualKeyCreateRequestDTO implements Serializable {

    private String keyName;

    private String ownerUserId;

    private String tenantId;

    /** 过期时间（yyyy-MM-dd HH:mm:ss，空为永不过期） */
    private String expiresAt;

    private Integer rpmLimit;

    private Integer dailyRequestLimit;

    private Integer dailyToolCallLimit;

    private Integer tpmLimit;

    private Double dailyCostLimit;

    /** IP/CIDR 白名单（空=不限，工单 0045） */
    private java.util.List<String> ipAllowList;

    /** 创建时一并授权的网关 ID 列表 */
    private java.util.List<String> grantGatewayIds;
}

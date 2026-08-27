package cn.chyuan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * 虚拟密钥↔网关多对多授权表（工单 0017）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpVirtualKeyGatewayPO implements Serializable {

    private Long id;

    /** mcp_virtual_key.id */
    private Long keyId;

    /** mcp_gateway.gateway_id */
    private String gatewayId;

    private Date grantedAt;
}

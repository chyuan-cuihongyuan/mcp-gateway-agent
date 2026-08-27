package cn.chyuan.ai.api;

import cn.chyuan.ai.api.dto.AuditLogResponseDTO;
import cn.chyuan.ai.api.dto.LoginRequestDTO;
import cn.chyuan.ai.api.dto.LoginResponseDTO;
import cn.chyuan.ai.api.dto.VirtualKeyCreateRequestDTO;
import cn.chyuan.ai.api.dto.VirtualKeyResponseDTO;
import cn.chyuan.ai.api.dto.VirtualKeyUpdateRequestDTO;
import cn.chyuan.ai.api.response.ResponsePage;

import java.util.List;

/**
 * admin 治理面服务接口（工单 0017：虚拟密钥/登录/审计）
 *
 * @author chyuan
 */
public interface IAdminGovernanceService {

    LoginResponseDTO login(LoginRequestDTO requestDTO);

    /** 创建密钥：明文凭证仅本次响应返回 */
    VirtualKeyResponseDTO createVirtualKey(VirtualKeyCreateRequestDTO requestDTO);

    VirtualKeyResponseDTO updateVirtualKey(Long id, VirtualKeyUpdateRequestDTO requestDTO);

    void revokeVirtualKey(Long id);

    void grantGateway(Long id, String gatewayId);

    void revokeGrantGateway(Long id, String gatewayId);

    VirtualKeyResponseDTO getVirtualKey(Long id);

    ResponsePage<List<VirtualKeyResponseDTO>> pageVirtualKeys(String keyword, int page, int size);

    ResponsePage<List<AuditLogResponseDTO>> pageAuditLogs(String resourceType, String resourceId, int page, int size);
}

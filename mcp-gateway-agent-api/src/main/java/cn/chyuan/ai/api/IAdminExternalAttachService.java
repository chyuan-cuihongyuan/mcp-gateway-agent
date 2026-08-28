package cn.chyuan.ai.api;

import cn.chyuan.ai.api.dto.ExternalAttachResponseDTO;
import cn.chyuan.ai.api.dto.ExternalAttachTestResponseDTO;
import cn.chyuan.ai.api.dto.ExternalAttachUpsertRequestDTO;

import java.util.List;

/**
 * admin 外部 MCP 挂接管理服务接口（工单 0021）
 *
 * @author chyuan
 */
public interface IAdminExternalAttachService {

    ExternalAttachResponseDTO createAttach(ExternalAttachUpsertRequestDTO requestDTO);

    ExternalAttachResponseDTO updateAttach(Long id, ExternalAttachUpsertRequestDTO requestDTO);

    void deleteAttach(Long id);

    ExternalAttachResponseDTO getAttach(Long id);

    List<ExternalAttachResponseDTO> listByGateway(String gatewayId);

    /** 按配置探活（未落库，保存前校验用） */
    ExternalAttachTestResponseDTO testAttach(ExternalAttachUpsertRequestDTO requestDTO);

    /** 按已存配置探活（保存后复检用，成功时回写连接状态） */
    ExternalAttachTestResponseDTO testAttachById(Long id);
}

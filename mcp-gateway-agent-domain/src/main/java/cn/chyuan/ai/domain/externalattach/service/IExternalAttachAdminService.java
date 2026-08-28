package cn.chyuan.ai.domain.externalattach.service;

import cn.chyuan.ai.domain.externalattach.model.valobj.ExternalAttachVO;

import java.util.List;

/**
 * 外部 MCP 挂接配置管理服务接口（工单 0021）
 *
 * @author chyuan
 */
public interface IExternalAttachAdminService {

    ExternalAttachVO create(ExternalAttachVO attach);

    ExternalAttachVO update(Long id, ExternalAttachVO attach);

    void delete(Long id);

    ExternalAttachVO get(Long id);

    List<ExternalAttachVO> listByGateway(String gatewayId);
}

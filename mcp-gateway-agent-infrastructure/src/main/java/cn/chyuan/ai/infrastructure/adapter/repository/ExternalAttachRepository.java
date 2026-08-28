package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.externalattach.adapter.repository.IExternalAttachRepository;
import cn.chyuan.ai.domain.externalattach.model.valobj.ExternalAttachVO;
import cn.chyuan.ai.infrastructure.dao.IExternalAttachDao;
import cn.chyuan.ai.infrastructure.dao.po.McpExternalAttachPO;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 外部 MCP 挂接配置仓储实现（工单 0021）
 *
 * @author chyuan
 */
@Repository
public class ExternalAttachRepository implements IExternalAttachRepository {

    @Resource
    private IExternalAttachDao externalAttachDao;

    @Override
    public Long insert(ExternalAttachVO attach) {
        McpExternalAttachPO po = toPo(attach);
        try {
            externalAttachDao.insert(po);
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("同网关下挂接名已存在: " + attach.getAttachName(), e);
        }
        return po.getId();
    }

    @Override
    public boolean update(ExternalAttachVO attach) {
        return externalAttachDao.update(toPo(attach)) > 0;
    }

    @Override
    public boolean deleteById(Long id) {
        return externalAttachDao.deleteById(id) > 0;
    }

    @Override
    public ExternalAttachVO findById(Long id) {
        return toVo(externalAttachDao.queryById(id));
    }

    @Override
    public List<ExternalAttachVO> findByGatewayId(String gatewayId) {
        return externalAttachDao.queryByGatewayId(gatewayId).stream().map(this::toVo).toList();
    }

    @Override
    public boolean updateConnectStatus(Long id, String connectStatus, String connectError) {
        return externalAttachDao.updateConnectStatus(id, connectStatus,
                connectError != null && connectError.length() > 1024
                        ? connectError.substring(0, 1024) : connectError) > 0;
    }

    private McpExternalAttachPO toPo(ExternalAttachVO vo) {
        if (vo == null) {
            return null;
        }
        return McpExternalAttachPO.builder()
                .id(vo.getId())
                .gatewayId(vo.getGatewayId())
                .attachName(vo.getAttachName())
                .transportType(vo.getTransportType())
                .endpoint(vo.getEndpoint())
                .apiKey(vo.getApiKey())
                .command(vo.getCommand())
                .args(vo.getArgs())
                .env(vo.getEnv())
                .requestTimeoutMs(vo.getRequestTimeoutMs())
                .status(vo.getStatus())
                .build();
    }

    private ExternalAttachVO toVo(McpExternalAttachPO po) {
        if (po == null) {
            return null;
        }
        return ExternalAttachVO.builder()
                .id(po.getId())
                .gatewayId(po.getGatewayId())
                .attachName(po.getAttachName())
                .transportType(po.getTransportType())
                .endpoint(po.getEndpoint())
                .apiKey(po.getApiKey())
                .command(po.getCommand())
                .args(po.getArgs())
                .env(po.getEnv())
                .requestTimeoutMs(po.getRequestTimeoutMs())
                .status(po.getStatus())
                .connectStatus(po.getConnectStatus())
                .connectError(po.getConnectError())
                .connectTime(po.getConnectTime())
                .createTime(po.getCreateTime())
                .updateTime(po.getUpdateTime())
                .build();
    }
}

package cn.chyuan.ai.domain.gateway.adapter.repository;

import cn.chyuan.ai.domain.gateway.model.entity.GatewayConfigCommandEntity;
import cn.chyuan.ai.domain.gateway.model.entity.GatewayToolConfigCommandEntity;
import cn.chyuan.ai.domain.gateway.model.valobj.GatewayToolConfigVO;

import java.util.List;

/**
 * 网关仓储服务接口
 *
 * @author chyuan
 *         2026/3/21 07:57
 */
public interface IGatewayRepository {

    void saveGatewayConfig(GatewayConfigCommandEntity commandEntity);

    void updateGatewayAuthStatus(GatewayConfigCommandEntity commandEntity);

    void saveGatewayToolConfig(GatewayToolConfigCommandEntity commandEntity);

    void updateGatewayToolProtocol(GatewayToolConfigCommandEntity commandEntity);

    void deleteGatewayToolConfig(Long toolId);

    List<GatewayToolConfigVO> queryGatewayToolConfigList(String gatewayId);

}

package cn.chyuan.ai.domain.gateway.service;

import cn.chyuan.ai.domain.gateway.model.entity.GatewayConfigCommandEntity;

/**
 * 网关配置接口
 *
 * @author chyuan
 *         2026/3/21 07:56
 */
public interface IGatewayConfigService {

    void saveGatewayConfig(GatewayConfigCommandEntity commandEntity);

    void updateGatewayAuthStatus(GatewayConfigCommandEntity commandEntity);

}

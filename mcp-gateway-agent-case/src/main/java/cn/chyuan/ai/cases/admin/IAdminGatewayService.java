package cn.chyuan.ai.cases.admin;

import cn.chyuan.ai.domain.gateway.model.entity.GatewayConfigCommandEntity;
import cn.chyuan.ai.domain.gateway.model.entity.GatewayToolConfigCommandEntity;

/**
 * 网关配置管理
 *
 * @author chyuan
 *         2026/3/24 08:09
 */
public interface IAdminGatewayService {

    void saveGatewayConfig(GatewayConfigCommandEntity commandEntity);

    void saveGatewayToolConfig(GatewayToolConfigCommandEntity commandEntity);

    void deleteGatewayToolConfig(Long toolId);

}

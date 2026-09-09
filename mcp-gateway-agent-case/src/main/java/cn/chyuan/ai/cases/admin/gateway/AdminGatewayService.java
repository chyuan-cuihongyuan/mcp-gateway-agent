package cn.chyuan.ai.cases.admin.gateway;

import cn.chyuan.ai.cases.admin.IAdminGatewayService;
import cn.chyuan.ai.domain.gateway.model.entity.GatewayConfigCommandEntity;
import cn.chyuan.ai.domain.gateway.model.entity.GatewayToolConfigCommandEntity;
import cn.chyuan.ai.domain.gateway.service.IGatewayConfigService;
import cn.chyuan.ai.domain.gateway.service.IGatewayToolConfigService;
import cn.chyuan.ai.domain.governance.service.ConfigHotReloadService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 网关配置管理
 *
 * @author chyuan
 *         2026/3/24 08:12
 */
@Slf4j
@Service
public class AdminGatewayService implements IAdminGatewayService {

    @Resource
    private IGatewayConfigService gatewayConfigService;

    @Resource
    private IGatewayToolConfigService gatewayToolConfigService;

    @Resource
    private ConfigHotReloadService configHotReloadService;

    @Override
    public void saveGatewayConfig(GatewayConfigCommandEntity commandEntity) {
        gatewayConfigService.saveGatewayConfig(commandEntity);
        configHotReloadService.notifyChange(ConfigHotReloadService.TYPE_GATEWAY_CONFIG,
                commandEntity.getGatewayConfigVO() == null ? null : commandEntity.getGatewayConfigVO().getGatewayId());
    }

    @Override
    public void saveGatewayToolConfig(GatewayToolConfigCommandEntity commandEntity) {
        gatewayToolConfigService.saveGatewayToolConfig(commandEntity);
        configHotReloadService.notifyChange(ConfigHotReloadService.TYPE_GATEWAY_CONFIG,
                commandEntity.getGatewayToolConfigVO() == null ? null : commandEntity.getGatewayToolConfigVO().getGatewayId());
    }

    @Override
    public void deleteGatewayToolConfig(Long toolId) {
        gatewayToolConfigService.deleteGatewayToolConfig(toolId);
        configHotReloadService.notifyChange(ConfigHotReloadService.TYPE_GATEWAY_CONFIG, null);
    }

}

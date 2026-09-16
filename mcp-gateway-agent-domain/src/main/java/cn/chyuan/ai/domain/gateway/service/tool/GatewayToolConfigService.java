package cn.chyuan.ai.domain.gateway.service.tool;

import cn.chyuan.ai.domain.gateway.adapter.repository.IGatewayRepository;
import cn.chyuan.ai.domain.gateway.model.entity.GatewayToolConfigCommandEntity;
import cn.chyuan.ai.domain.gateway.model.valobj.GatewayToolConfigVO;
import cn.chyuan.ai.domain.gateway.service.IGatewayToolConfigService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 网关工具配置服务实现
 *
 * @author chyuan
 *         2026/3/21 09:43
 */
@Slf4j
@Service
public class GatewayToolConfigService implements IGatewayToolConfigService {

    @Resource
    private IGatewayRepository repository;

    @Override
    public void saveGatewayToolConfig(GatewayToolConfigCommandEntity commandEntity) {
        // SELFLOOP7 loop-812（OWASP MCP03）：描述进入 LLM 上下文前做投毒内容闸（fail-closed）
        if (commandEntity.getGatewayToolConfigVO() != null) {
            ToolDescriptionGuard.check(commandEntity.getGatewayToolConfigVO().getToolDescription());
        }
        repository.saveGatewayToolConfig(commandEntity);
    }

    @Override
    public void updateGatewayToolProtocol(GatewayToolConfigCommandEntity commandEntity) {
        repository.updateGatewayToolProtocol(commandEntity);
    }

    @Override
    public void deleteGatewayToolConfig(Long toolId) {
        repository.deleteGatewayToolConfig(toolId);
    }

    @Override
    public List<GatewayToolConfigVO> queryGatewayToolConfigList(String gatewayId) {
        return repository.queryGatewayToolConfigList(gatewayId);
    }

}

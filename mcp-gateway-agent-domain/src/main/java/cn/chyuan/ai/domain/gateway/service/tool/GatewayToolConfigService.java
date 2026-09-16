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
        audit("TOOL_CONFIG_SAVE", commandEntity);
    }

    @Override
    public void updateGatewayToolProtocol(GatewayToolConfigCommandEntity commandEntity) {
        repository.updateGatewayToolProtocol(commandEntity);
        audit("TOOL_CONFIG_UPDATE_PROTOCOL", commandEntity);
    }

    @Override
    public void deleteGatewayToolConfig(Long toolId) {
        repository.deleteGatewayToolConfig(toolId);
        // SELFLOOP7 loop-814：配置变更审计行（日志即事件流，Loki 可溯；不落描述全文）
        log.info("audit: action=TOOL_CONFIG_DELETE resourceId={} resourceType=mcp_gateway_tool", toolId);
    }

    @Override
    public List<GatewayToolConfigVO> queryGatewayToolConfigList(String gatewayId) {
        return repository.queryGatewayToolConfigList(gatewayId);
    }

    /**
     * SELFLOOP7 loop-814：配置变更审计行——结构化 key=value（HttpEventLogger 同构），
     * 记 action/resourceId/描述长度/guard 通过情况；描述全文不落日志（大字段纪律）。
     */
    private void audit(String action, GatewayToolConfigCommandEntity commandEntity) {
        GatewayToolConfigVO vo = commandEntity.getGatewayToolConfigVO();
        if (vo == null) {
            log.info("audit: action={} resourceType=mcp_gateway_tool", action);
            return;
        }
        int descLength = vo.getToolDescription() == null ? 0 : vo.getToolDescription().length();
        log.info("audit: action={} resourceId={} resourceType=mcp_gateway_tool gatewayId={} toolName={} descLength={}",
                action, vo.getToolId(), vo.getGatewayId(), vo.getToolName(), descLength);
    }

}

package cn.chyuan.ai.domain.session.adapter.repository;

import cn.chyuan.ai.domain.session.model.valobj.gateway.McpGatewayConfigVO;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolConfigVO;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolProtocolConfigVO;

import java.util.List;

/**
 * 会话仓储接口
 *
 * @author chyuan
 *         2026/1/13 07:49
 */
public interface ISessionRepository {

    McpGatewayConfigVO queryMcpGatewayConfigByGatewayId(String gatewayId);

    List<McpToolConfigVO> queryMcpGatewayToolConfigListByGatewayId(String gatewayId);

    McpToolProtocolConfigVO queryMcpGatewayProtocolConfig(String gatewayId, String toolName);

    /**
     * 清理指定网关的所有配置缓存
     */
    default void evictGateway(String gatewayId) {}

    /**
     * 清理指定工具协议配置缓存
     */
    default void evictToolProtocol(String gatewayId, String toolName) {}

}

package cn.chyuan.ai.domain.session.service;

import cn.chyuan.ai.domain.session.adapter.repository.ISessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 工具配置缓存清理服务 — 管理后台修改工具/协议/网关配置后调用此服务清理缓存
 */
@Slf4j
@Service
public class ToolConfigCacheEvictService {

    private final ISessionRepository sessionRepository;

    public ToolConfigCacheEvictService(ISessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /**
     * 清理指定网关的所有配置缓存（网关配置 + 工具列表）
     */
    public void evictGateway(String gatewayId) {
        sessionRepository.evictGateway(gatewayId);
        log.info("已清理网关配置缓存: gatewayId={}", gatewayId);
    }

    /**
     * 清理指定工具协议配置缓存
     */
    public void evictToolProtocol(String gatewayId, String toolName) {
        sessionRepository.evictToolProtocol(gatewayId, toolName);
        log.info("已清理工具协议缓存: gatewayId={}, toolName={}", gatewayId, toolName);
    }
}

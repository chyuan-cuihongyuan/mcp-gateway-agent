package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.externalattach.adapter.port.IExternalMcpAttachPort;
import cn.chyuan.ai.domain.governance.adapter.IConfigEventBus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 配置热更新协调服务（工单 0078）
 *
 * <p>写路径调 {@link #notifyChange} 发布跨实例事件；订阅侧回调
 * {@link #onRemoteEvent} 失效本实例本地缓存（CEL 快照/密钥缓存/挂接注册表）。
 * 自实例事件跳过（写路径已就地失效）；30s TTL 兜底保留——事件丢失或
 * Redis 故障时语义退化为纯 TTL，不 fail 任何请求。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class ConfigHotReloadService {

    public static final String TYPE_VIRTUAL_KEY = "VIRTUAL_KEY";
    public static final String TYPE_CEL_RULE = "CEL_RULE";
    public static final String TYPE_ATTACH = "ATTACH";
    public static final String TYPE_LLM_CHANNEL = "LLM_CHANNEL";
    public static final String TYPE_GATEWAY_CONFIG = "GATEWAY_CONFIG";

    /** 本实例标识（订阅侧跳过自己发布的事件） */
    private final String instanceId = UUID.randomUUID().toString();

    @Resource
    private ObjectProvider<IConfigEventBus> configEventBusProvider;

    /** 懒解析依赖：写路径服务（CEL/密钥）会注入本服务，直接引用将成环 */
    @Resource
    private ObjectProvider<ICelRuleService> celRuleServiceProvider;

    @Resource
    private ObjectProvider<IGovernanceAuthService> governanceAuthServiceProvider;

    @Resource
    private ObjectProvider<IExternalMcpAttachPort> externalMcpAttachPortProvider;

    @PostConstruct
    void logStartup() {
        log.info("配置热更新协调就绪 instanceId={}（事件总线 {}）", instanceId,
                configEventBusProvider.getIfAvailable() != null ? "已装配" : "未装配——退化为纯 TTL");
    }

    /** 写路径发布配置变更（尽力而为：失败只记日志，不影响写结果） */
    public void notifyChange(String objectType, String objectId) {
        IConfigEventBus bus = configEventBusProvider.getIfAvailable();
        if (bus == null) {
            return;
        }
        try {
            bus.publish(objectType, objectId);
        } catch (Exception e) {
            log.warn("配置变更事件发布失败（退化为 TTL 兜底）type={} id={}: {}", objectType, objectId, e.getMessage());
        }
    }

    /** 订阅侧回调：失效本实例对应本地缓存（自实例事件跳过） */
    public void onRemoteEvent(String objectType, String objectId, String sourceInstanceId) {
        if (instanceId.equals(sourceInstanceId)) {
            return;
        }
        try {
            switch (objectType == null ? "" : objectType) {
                case TYPE_VIRTUAL_KEY -> {
                    IGovernanceAuthService authService = governanceAuthServiceProvider.getIfAvailable();
                    if (authService != null) {
                        authService.invalidateAll();
                    }
                }
                case TYPE_CEL_RULE -> {
                    ICelRuleService celRuleService = celRuleServiceProvider.getIfAvailable();
                    if (celRuleService != null) {
                        celRuleService.invalidateSnapshot();
                    }
                }
                case TYPE_ATTACH, TYPE_GATEWAY_CONFIG -> {
                    IExternalMcpAttachPort port = externalMcpAttachPortProvider.getIfAvailable();
                    if (port != null && objectId != null && !objectId.isBlank()) {
                        port.evictGateway(objectId);
                    }
                    IGovernanceAuthService authService = governanceAuthServiceProvider.getIfAvailable();
                    if (authService != null) {
                        authService.invalidateAll();
                    }
                }
                case TYPE_LLM_CHANNEL -> {
                    // LLM 渠道无本地缓存（逐请求读取），此处仅作事件可见性记录
                    log.debug("LLM 渠道配置已跨实例变更：{}", objectId);
                }
                default -> log.debug("未知配置变更类型（忽略）：{}", objectType);
            }
            log.info("配置热更新生效 type={} id={} source={}", objectType, objectId, sourceInstanceId);
        } catch (Exception e) {
            log.warn("配置热更新失效处理异常（TTL 兜底）type={} id={}: {}", objectType, objectId, e.getMessage());
        }
    }

    public String getInstanceId() {
        return instanceId;
    }
}

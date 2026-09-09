package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.externalattach.adapter.port.IExternalMcpAttachPort;
import cn.chyuan.ai.domain.governance.adapter.IConfigEventBus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 配置热更新协调服务测试（工单 0078）
 */
@DisplayName("配置热更新协调服务测试")
class ConfigHotReloadServiceTest {

    private IConfigEventBus bus;
    private ICelRuleService celRuleService;
    private IGovernanceAuthService governanceAuthService;
    private IExternalMcpAttachPort attachPort;
    private ConfigHotReloadService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        bus = Mockito.mock(IConfigEventBus.class);
        celRuleService = Mockito.mock(ICelRuleService.class);
        governanceAuthService = Mockito.mock(IGovernanceAuthService.class);
        attachPort = Mockito.mock(IExternalMcpAttachPort.class);

        ObjectProvider<IConfigEventBus> busProvider = Mockito.mock(ObjectProvider.class);
        Mockito.when(busProvider.getIfAvailable()).thenReturn(bus);
        ObjectProvider<ICelRuleService> celProvider = Mockito.mock(ObjectProvider.class);
        Mockito.when(celProvider.getIfAvailable()).thenReturn(celRuleService);
        ObjectProvider<IGovernanceAuthService> authProvider = Mockito.mock(ObjectProvider.class);
        Mockito.when(authProvider.getIfAvailable()).thenReturn(governanceAuthService);
        ObjectProvider<IExternalMcpAttachPort> portProvider = Mockito.mock(ObjectProvider.class);
        Mockito.when(portProvider.getIfAvailable()).thenReturn(attachPort);

        service = new ConfigHotReloadService();
        for (java.lang.reflect.Field field : ConfigHotReloadService.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                continue;
            }
            field.setAccessible(true);
            if (field.getType() == ObjectProvider.class) {
                if (field.getName().equals("configEventBusProvider")) {
                    field.set(service, busProvider);
                } else if (field.getName().equals("celRuleServiceProvider")) {
                    field.set(service, celProvider);
                } else if (field.getName().equals("governanceAuthServiceProvider")) {
                    field.set(service, authProvider);
                } else if (field.getName().equals("externalMcpAttachPortProvider")) {
                    field.set(service, portProvider);
                }
            }
        }
    }

    @Test
    @DisplayName("写路径发布：类型与对象标识透传")
    void notifyChangePublishes() {
        service.notifyChange(ConfigHotReloadService.TYPE_CEL_RULE, "42");
        Mockito.verify(bus).publish(ConfigHotReloadService.TYPE_CEL_RULE, "42");
    }

    @Test
    @DisplayName("总线未装配或发布失败：不抛错不影响写路径")
    void publishFailureSwallowed() {
        Mockito.doThrow(new RuntimeException("redis down")).when(bus).publish(Mockito.anyString(), Mockito.any());
        Assertions.assertDoesNotThrow(
                () -> service.notifyChange(ConfigHotReloadService.TYPE_VIRTUAL_KEY, null));
    }

    @Test
    @DisplayName("自实例事件跳过（写路径已就地失效）")
    void selfEventSkipped() {
        bus.publish(ConfigHotReloadService.TYPE_CEL_RULE, "1");
        Mockito.verify(bus).publish(ConfigHotReloadService.TYPE_CEL_RULE, "1");
        // 以本实例 id 回放：不应触发任何失效
        service.onRemoteEvent(ConfigHotReloadService.TYPE_CEL_RULE, "1", service.getInstanceId());
        Mockito.verify(celRuleService, Mockito.never()).invalidateSnapshot();
        Mockito.verify(governanceAuthService, Mockito.never()).invalidateAll();
    }

    @Test
    @DisplayName("跨实例事件按类型路由失效：密钥→认证缓存，规则→CEL 快照，渠道→注册表+认证缓存")
    void remoteEventRoutes() {
        service.onRemoteEvent(ConfigHotReloadService.TYPE_VIRTUAL_KEY, null, "other-instance");
        Mockito.verify(governanceAuthService).invalidateAll();

        service.onRemoteEvent(ConfigHotReloadService.TYPE_CEL_RULE, "9", "other-instance");
        Mockito.verify(celRuleService).invalidateSnapshot();

        service.onRemoteEvent(ConfigHotReloadService.TYPE_ATTACH, "gateway_001", "other-instance");
        Mockito.verify(attachPort).evictGateway("gateway_001");
        Mockito.verify(governanceAuthService, Mockito.times(2)).invalidateAll();
    }

    @Test
    @DisplayName("失效处理异常不抛错（TTL 兜底）+ 未知类型忽略")
    void invalidationFailureSwallowed() {
        Mockito.doThrow(new RuntimeException("boom")).when(celRuleService).invalidateSnapshot();
        Assertions.assertDoesNotThrow(
                () -> service.onRemoteEvent(ConfigHotReloadService.TYPE_CEL_RULE, "1", "other"));
        Assertions.assertDoesNotThrow(
                () -> service.onRemoteEvent("UNKNOWN_TYPE", "x", "other"));
    }
}

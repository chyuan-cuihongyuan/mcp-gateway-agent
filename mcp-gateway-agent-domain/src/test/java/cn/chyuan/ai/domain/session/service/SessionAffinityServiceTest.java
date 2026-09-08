package cn.chyuan.ai.domain.session.service;

import cn.chyuan.ai.domain.session.adapter.repository.ISessionRouteRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 会话亲和服务测试（工单 0055：登记/释放/漂移检测/降级）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("会话亲和服务测试")
public class SessionAffinityServiceTest {

    @Mock
    private ISessionRouteRepository routeRepository;

    @InjectMocks
    private SessionAffinityService service;

    @Test
    @DisplayName("生命周期 — 登记带 TTL、终止移除（与会话超时一致口径）")
    public void testRegisterAndRelease() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "configuredInstanceId", "inst-1");

        service.register("sess-1", Duration.ofMinutes(30));
        verify(routeRepository).save("sess-1", "inst-1", Duration.ofMinutes(30));

        service.release("sess-1");
        verify(routeRepository).delete("sess-1");
    }

    @Test
    @DisplayName("漂移检测 — 归属非本机计漂移并返回 true；本机 false；未登记 false")
    public void testDriftDetection() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "configuredInstanceId", "inst-1");
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "meterRegistry", meters);

        when(routeRepository.findInstance("sess-other")).thenReturn("inst-2");
        when(routeRepository.findInstance("sess-self")).thenReturn("inst-1");
        when(routeRepository.findInstance("sess-none")).thenReturn(null);

        assertTrue(service.checkDrift("sess-other"));
        assertEquals(1.0, meters.counter("gateway.session.drift", "gateway", "").count());
        assertFalse(service.checkDrift("sess-self"));
        assertFalse(service.checkDrift("sess-none"));
    }

    @Test
    @DisplayName("降级 — 路由表异常静默（不外抛不影响请求面）")
    public void testDegradeOnRepositoryFailure() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "configuredInstanceId", "i");
        doThrow(new RuntimeException("redis down")).when(routeRepository).save(anyString(), anyString(), any());
        assertDoesNotThrow(() -> service.register("s", Duration.ofMinutes(1)));
        when(routeRepository.findInstance(anyString())).thenThrow(new RuntimeException("redis down"));
        assertFalse(service.checkDrift("s"));
    }

    @Test
    @DisplayName("实例标识 — 显式配置优先；未配置派生主机名-随机后缀（非空）")
    public void testInstanceIdResolution() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "configuredInstanceId", "explicit");
        assertEquals("explicit", service.instanceId());

        SessionAffinityService derived = new SessionAffinityService();
        org.springframework.test.util.ReflectionTestUtils.setField(derived, "configuredInstanceId", "");
        assertNotNull(derived.instanceId());
        assertTrue(derived.instanceId().length() > 4);
    }
}

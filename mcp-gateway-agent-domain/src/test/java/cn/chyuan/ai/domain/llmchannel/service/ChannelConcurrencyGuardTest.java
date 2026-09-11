package cn.chyuan.ai.domain.llmchannel.service;

import cn.chyuan.ai.domain.llmchannel.model.valobj.LlmChannelVO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;

/**
 * 渠道并发闸测试（工单 0161：信号量获取/超时/释放，顺序模拟并发；gauge；不配置兼容）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("渠道并发闸测试")
public class ChannelConcurrencyGuardTest {

    @Mock
    private org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistryProvider;

    private ChannelConcurrencyGuard guard;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @BeforeEach
    void wire() {
        lenient().when(meterRegistryProvider.getIfAvailable()).thenReturn(registry);
        guard = new ChannelConcurrencyGuard(meterRegistryProvider);
        ReflectionTestUtils.setField(guard, "queueTimeoutMs", 1L);
    }

    private static LlmChannelVO channel(Long id, String name, Integer maxConcurrency) {
        return LlmChannelVO.builder()
                .id(id).name(name).baseUrl("http://upstream-" + name).models("m")
                .status(LlmChannelVO.STATUS_ENABLED).maxConcurrency(maxConcurrency).build();
    }

    @Test
    @DisplayName("顺序模拟并发 — 限额 1：占满后超时拒绝，释放后可再获取")
    public void testAcquireTimeoutRelease() {
        LlmChannelVO channel = channel(1L, "single", 1);

        assertTrue(guard.tryAcquire(channel), "首占成功");
        assertEquals(1, guard.inFlight(1L));

        assertFalse(guard.tryAcquire(channel), "第二个请求排队超时被拒");
        assertEquals(1, guard.inFlight(1L), "被拒请求不占额度");

        guard.release(channel);
        assertEquals(0, guard.inFlight(1L));
        assertTrue(guard.tryAcquire(channel), "释放后可再获取");
    }

    @Test
    @DisplayName("限额 2 — 两占满一拒；释放按序恢复")
    public void testLimitTwo() {
        LlmChannelVO channel = channel(2L, "duo", 2);
        List<Boolean> results = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            results.add(guard.tryAcquire(channel));
        }
        assertEquals(List.of(true, true, false), results, "两占一拒");
        assertEquals(2, guard.inFlight(2L));
        guard.release(channel);
        guard.release(channel);
        assertEquals(0, guard.inFlight(2L));
    }

    @Test
    @DisplayName("不配置（null/0）= 不限制恒放行（兼容存量）")
    public void testUnlimitedPassthrough() {
        LlmChannelVO unconfigured = channel(3L, "free", null);
        LlmChannelVO zero = channel(4L, "zero", 0);
        for (int i = 0; i < 10; i++) {
            assertTrue(guard.tryAcquire(unconfigured));
            assertTrue(guard.tryAcquire(zero));
        }
        assertEquals(0, guard.inFlight(3L), "不限渠道不计数");
    }

    @Test
    @DisplayName("gauge channel_active_requests — 随占位/释放变化")
    public void testGauge() {
        LlmChannelVO channel = channel(5L, "gauged", 2);
        guard.tryAcquire(channel);
        assertEquals(1.0, registry.get("channel_active_requests")
                .tag("channel", "gauged").gauge().value());
        guard.tryAcquire(channel);
        assertEquals(2.0, registry.get("channel_active_requests")
                .tag("channel", "gauged").gauge().value());
        guard.release(channel);
        guard.release(channel);
        assertEquals(0.0, registry.get("channel_active_requests")
                .tag("channel", "gauged").gauge().value());
    }
}

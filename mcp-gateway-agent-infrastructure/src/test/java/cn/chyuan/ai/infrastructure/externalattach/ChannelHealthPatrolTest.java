package cn.chyuan.ai.infrastructure.externalattach;

import cn.chyuan.ai.domain.externalattach.adapter.port.IExternalMcpAttachPort;
import cn.chyuan.ai.domain.externalattach.adapter.repository.IExternalAttachRepository;
import cn.chyuan.ai.domain.externalattach.model.valobj.ExternalAttachVO;
import cn.chyuan.ai.domain.governance.adapter.IGovernanceEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 渠道健康巡检测试（工单 0058：连击禁用/成功恢复/手动禁用跳过/事件发布）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("渠道健康巡检测试")
public class ChannelHealthPatrolTest {

    @Mock
    private IExternalAttachRepository attachRepository;

    @Mock
    private IExternalMcpAttachPort attachPort;

    @Mock
    private IGovernanceEventPublisher eventPublisher;

    @InjectMocks
    private ChannelHealthPatrol patrol;

    private static ExternalAttachVO attach(long id, int status) {
        return ExternalAttachVO.builder()
                .id(id).gatewayId("gateway_001").attachName("ch" + id).status(status)
                .transportType(ExternalAttachVO.TRANSPORT_STREAMABLE_HTTP)
                .endpoint("http://upstream.local/mcp")
                .build();
    }

    @Test
    @DisplayName("连击自动禁用 — 失败达阈值置 AUTO_DISABLED + 冷却 + 事件；未达阈值只计数")
    public void testFailureStreakAutoDisables() {
        when(attachRepository.findAllAttaches()).thenReturn(List.of(attach(1L, 1)));
        when(attachPort.probeConnect(any())).thenReturn(new IExternalMcpAttachPort.ConnectState(false, 0, "conn refused"));
        org.springframework.test.util.ReflectionTestUtils.setField(patrol, "failThreshold", 3);
        org.springframework.test.util.ReflectionTestUtils.setField(patrol, "cooldownSeconds", 120L);

        patrol.patrolOnceSafely();
        patrol.patrolOnceSafely();
        verify(attachRepository, never()).updateChannelStatus(anyLong(), anyInt(), any());

        patrol.patrolOnceSafely();
        ArgumentCaptor<Date> cooldown = ArgumentCaptor.forClass(Date.class);
        verify(attachRepository).updateChannelStatus(eq(1L), eq(ExternalAttachVO.STATUS_AUTO_DISABLED), cooldown.capture());
        assertTrue(cooldown.getValue().getTime() > System.currentTimeMillis());
        verify(eventPublisher).publish(eq(ChannelHealthPatrol.EVENT_CHANNEL_AUTO_DISABLED), anyMap());
    }

    @Test
    @DisplayName("半开恢复 — AUTO_DISABLED 渠道探测成功一次即恢复 + 计数清零 + 事件")
    public void testRecoveryOnProbeSuccess() {
        when(attachRepository.findAllAttaches()).thenReturn(List.of(attach(2L, 2)));
        when(attachPort.probeConnect(any())).thenReturn(new IExternalMcpAttachPort.ConnectState(true, 4, null));

        patrol.patrolOnceSafely();

        verify(attachRepository).updateChannelHealth(eq(2L), anyLong());
        verify(attachRepository).updateChannelStatus(2L, ExternalAttachVO.STATUS_ENABLED, null);
        verify(eventPublisher).publish(eq(ChannelHealthPatrol.EVENT_CHANNEL_RECOVERED), anyMap());
    }

    @Test
    @DisplayName("手动禁用跳过 — 不探测不被自动恢复；启用态成功仅记健康")
    public void testManualDisabledSkipped() {
        when(attachRepository.findAllAttaches()).thenReturn(List.of(
                attach(3L, 0), attach(4L, 1)));
        when(attachPort.probeConnect(any())).thenReturn(new IExternalMcpAttachPort.ConnectState(true, 8, null));

        patrol.patrolOnceSafely();

        verify(attachPort, times(1)).probeConnect(argThat(vo -> vo.getId() == 4L));
        verify(attachRepository, never()).updateChannelStatus(anyLong(), anyInt(), any());
    }

    @Test
    @DisplayName("巡检异常退避 — 仓储抛错不外抛（下轮重试）")
    public void testPatrolToleratesRepositoryFailure() {
        when(attachRepository.findAllAttaches()).thenThrow(new RuntimeException("db down"));
        assertDoesNotThrow(patrol::patrolOnceSafely);
    }

    private static Map<String, Object> anyMap() {
        return any();
    }
}

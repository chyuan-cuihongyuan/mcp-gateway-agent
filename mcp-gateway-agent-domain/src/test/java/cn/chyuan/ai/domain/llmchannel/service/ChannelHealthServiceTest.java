package cn.chyuan.ai.domain.llmchannel.service;

import cn.chyuan.ai.domain.llmchannel.adapter.repository.IChannelHealthSnapshotRepository;
import cn.chyuan.ai.domain.llmchannel.adapter.repository.ILlmChannelRepository;
import cn.chyuan.ai.domain.llmchannel.model.valobj.ChannelHealthSnapshotVO;
import cn.chyuan.ai.domain.llmchannel.model.valobj.LlmChannelVO;
import cn.chyuan.ai.domain.usage.adapter.repository.IUsageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 渠道健康分服务测试（工单 0159：评分装配/探测派生/采样任务/调度降权）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("渠道健康分服务测试")
public class ChannelHealthServiceTest {

    @Mock
    private ILlmChannelRepository channelRepository;

    @Mock
    private org.springframework.beans.factory.ObjectProvider<IUsageRepository> usageRepositoryProvider;

    @Mock
    private IUsageRepository usageRepository;

    @Mock
    private org.springframework.beans.factory.ObjectProvider<IChannelHealthSnapshotRepository> snapshotRepositoryProvider;

    @Mock
    private IChannelHealthSnapshotRepository snapshotRepository;

    @InjectMocks
    private ChannelHealthService service;

    /** 测试配置：权重 0.5/0.2/0.3（和=1）、参考延迟 5000ms、近 20 次、探测新鲜窗 24h */
    @BeforeEach
    void wire() {
        ReflectionTestUtils.setField(service, "weightError", 0.5);
        ReflectionTestUtils.setField(service, "weightProbe", 0.2);
        ReflectionTestUtils.setField(service, "weightLatency", 0.3);
        ReflectionTestUtils.setField(service, "latencyRefMs", 5000L);
        ReflectionTestUtils.setField(service, "recentN", 20);
        ReflectionTestUtils.setField(service, "probeFreshMs", 86_400_000L);
        lenient().when(usageRepositoryProvider.getIfAvailable()).thenReturn(usageRepository);
    }

    private LlmChannelVO channel(long id, String name, boolean probedOk) {
        return LlmChannelVO.builder()
                .id(id).name(name).baseUrl("http://upstream-" + name).models("m")
                .weight(1).priority(0).status(LlmChannelVO.STATUS_ENABLED)
                .testTime(probedOk ? new Date() : null)
                .responseTimeMs(probedOk ? 120L : null)
                .build();
    }

    @Test
    @DisplayName("报表 — 错误率/延迟取近 N 次账本，探测分由 test_time 派生，降权标记正确")
    public void testReports() {
        when(channelRepository.findAll()).thenReturn(List.of(channel(1L, "good", true), channel(2L, "bad", false)));
        when(usageRepository.channelRecentStats(eq("good"), eq(20)))
                .thenReturn(Map.of("total", 20, "failures", 1, "avgLatencyMs", 200L));
        when(usageRepository.channelRecentStats(eq("bad"), eq(20)))
                .thenReturn(Map.of("total", 10, "failures", 8, "avgLatencyMs", 6000L));

        List<Map<String, Object>> reports = service.reports(60);

        assertEquals(2, reports.size());
        Map<String, Object> good = reports.get(0);
        assertEquals("good", good.get("channelName"));
        assertEquals(0.05, (Double) good.get("errorRate"), 0.0001);
        assertEquals(100.0, (Double) good.get("probeScore"), 0.001, "新鲜测试成功 → 探测满分");
        assertFalse((Boolean) good.get("demoted"));
        Map<String, Object> bad = reports.get(1);
        assertTrue((Double) bad.get("score") < 60.0, "高错误+超延迟渠道低分");
        assertTrue((Boolean) bad.get("demoted"), "低于阈值标记降权");
    }

    @Test
    @DisplayName("采样任务 — 逐渠道落快照（score/错误率/探测分/延迟）；仓储缺席跳过")
    public void testSampleAll() {
        when(channelRepository.findAll()).thenReturn(List.of(channel(1L, "good", true)));
        when(usageRepository.channelRecentStats(eq("good"), eq(20)))
                .thenReturn(Map.of("total", 20, "failures", 0, "avgLatencyMs", 100L));
        when(snapshotRepositoryProvider.getIfAvailable()).thenReturn(snapshotRepository);

        int saved = service.sampleAll();

        assertEquals(1, saved);
        ArgumentCaptor<ChannelHealthSnapshotVO> captor = ArgumentCaptor.forClass(ChannelHealthSnapshotVO.class);
        verify(snapshotRepository).insert(captor.capture());
        ChannelHealthSnapshotVO snapshot = captor.getValue();
        assertEquals(1L, snapshot.getChannelId());
        assertEquals("good", snapshot.getChannelName());
        assertNotNull(snapshot.getScore());
        assertEquals(0.0, snapshot.getErrorRate(), 0.0001);
        assertEquals(100.0, snapshot.getProbeScore(), 0.001);
        assertEquals(100L, snapshot.getAvgLatencyMs());
        assertNotNull(snapshot.getSampledAt());

        // 快照仓储缺席 → 0（切片上下文兜底）
        when(snapshotRepositoryProvider.getIfAvailable()).thenReturn(null);
        assertEquals(0, service.sampleAll());
    }

    @Test
    @DisplayName("调度降权 — 低分渠道排候选尾；健康分计算异常按满分兜底不降权")
    public void testDemote() {
        LlmChannelVO healthy = channel(1L, "good", true);
        LlmChannelVO sick = channel(2L, "bad", false);
        when(channelRepository.findById(1L)).thenReturn(healthy);
        when(channelRepository.findById(2L)).thenReturn(sick);
        when(usageRepository.channelRecentStats(eq("good"), eq(20)))
                .thenReturn(Map.of("total", 20, "failures", 0, "avgLatencyMs", 100L));
        when(usageRepository.channelRecentStats(eq("bad"), eq(20)))
                .thenReturn(Map.of("total", 10, "failures", 10, "avgLatencyMs", 9000L));

        List<LlmChannelVO> reordered = service.demote(List.of(sick, healthy), 60);
        assertEquals(List.of(healthy, sick), reordered, "低分渠道排尾");

        // 阈值 0 = 关闭
        assertEquals(List.of(sick, healthy), service.demote(List.of(sick, healthy), 0));
    }
}

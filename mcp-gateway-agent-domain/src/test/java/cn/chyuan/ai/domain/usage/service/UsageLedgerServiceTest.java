package cn.chyuan.ai.domain.usage.service;

import cn.chyuan.ai.domain.usage.adapter.repository.IUsageRepository;
import cn.chyuan.ai.domain.usage.model.valobj.DailyUsageVO;
import cn.chyuan.ai.domain.usage.model.valobj.UsageRecordVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 用量账本服务测试（工单 0046：异步落账字段完整/聚合增量/失败不阻断/默认值补齐）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("用量账本服务测试")
public class UsageLedgerServiceTest {

    @Mock
    private IUsageRepository repository;

    @InjectMocks
    private UsageLedgerService service;

    @Test
    @DisplayName("record — 异步落明细并聚合日增量（requestId/时间补默认值、失败不计 fail）")
    public void testRecord_InsertsLogAndUpsertsDaily() {
        service.record(UsageRecordVO.builder()
                .virtualKeyId(42L)
                .gatewayId("gateway_001")
                .trafficType("MCP")
                .toolOrModel("queryOilOrder")
                .status("SUCCESS")
                .durationMs(120)
                .build());

        ArgumentCaptor<UsageRecordVO> logCaptor = ArgumentCaptor.forClass(UsageRecordVO.class);
        ArgumentCaptor<DailyUsageVO> dailyCaptor = ArgumentCaptor.forClass(DailyUsageVO.class);
        verify(repository, timeout(2000)).insert(logCaptor.capture());
        verify(repository, timeout(2000)).upsertDaily(dailyCaptor.capture());

        UsageRecordVO logged = logCaptor.getValue();
        assertNotNull(logged.getRequestId(), "requestId 应自动补齐");
        assertNotNull(logged.getCreatedAt(), "createdAt 应自动补齐");

        DailyUsageVO delta = dailyCaptor.getValue();
        assertEquals(0L, delta.getFailCount(), "成功调用 fail 增量 0");
        assertEquals(1L, delta.getCallCount());
        assertEquals(120L, delta.getTotalDurationMs());
        assertEquals("42", String.valueOf(delta.getVirtualKeyId()));
    }

    @Test
    @DisplayName("record — 失败调用 fail 增量 1；token 合计入 tokenSum")
    public void testRecord_FailDeltaAndTokens() {
        service.record(UsageRecordVO.builder()
                .trafficType("LLM")
                .toolOrModel("deepseek-v4-pro")
                .status("FAIL")
                .durationMs(30)
                .promptTokens(100L)
                .completionTokens(50L)
                .build());

        ArgumentCaptor<DailyUsageVO> dailyCaptor = ArgumentCaptor.forClass(DailyUsageVO.class);
        verify(repository, timeout(2000)).upsertDaily(dailyCaptor.capture());
        assertEquals(1L, dailyCaptor.getValue().getFailCount());
        assertEquals(0L, dailyCaptor.getValue().getVirtualKeyId(), "匿名哨兵 0");
        assertEquals(150L, dailyCaptor.getValue().getTokenSum(), "prompt+completion 合计");
    }

    @Test
    @DisplayName("record — 落库异常被吞（不影响主链，返回即成功）")
    public void testRecord_SwallowsRepositoryFailure() {
        // 异步执行时序先于 Mockito 严格桩检查，用 lenient
        lenient().doThrow(new RuntimeException("db down")).when(repository).insert(any());
        assertDoesNotThrow(() -> service.record(UsageRecordVO.builder()
                .toolOrModel("t").status("SUCCESS").build()));
    }

    @Test
    @DisplayName("record — null 记录直接忽略")
    public void testRecord_NullIgnored() {
        assertDoesNotThrow(() -> service.record(null));
        verifyNoInteractions(repository);
    }
}

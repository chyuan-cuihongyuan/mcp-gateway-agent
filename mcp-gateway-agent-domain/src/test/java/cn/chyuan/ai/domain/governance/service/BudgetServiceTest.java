package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.adapter.IGovernanceEventPublisher;
import cn.chyuan.ai.domain.governance.adapter.repository.IVirtualKeyRepository;
import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import cn.chyuan.ai.domain.governance.model.valobj.VirtualKeyVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 预算服务测试（工单 0050：软硬线/窗口惰性重置/事件/零开销直通）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("预算服务测试")
public class BudgetServiceTest {

    @Mock
    private IVirtualKeyRepository repository;

    @Mock
    private IGovernanceEventPublisher eventPublisher;

    /** 账本端口（工单 0087）：ObjectProvider mock */
    @Mock
    private org.springframework.beans.factory.ObjectProvider<cn.chyuan.ai.domain.usage.adapter.repository.IUsageRepository> usageRepositoryProvider;

    @Mock
    private cn.chyuan.ai.domain.usage.adapter.repository.IUsageRepository usageRepository;

    @InjectMocks
    private BudgetService service;

    @SuppressWarnings("unchecked")
    private void stubUsageRepository() {
        // lenient：未触达账本的用例（早退路径）不因未用 stub 报错
        org.mockito.Mockito.lenient().when(usageRepositoryProvider.getIfAvailable()).thenReturn(usageRepository);
    }

    private GovernancePrincipal principalWithBudget(Long soft, Long hard) {
        return GovernancePrincipal.builder()
                .authType(GovernancePrincipal.AuthType.VIRTUAL_KEY)
                .virtualKeyId(42L)
                .budgetSoft(soft)
                .budgetHard(hard)
                .build();
    }

    // ---- 金额预算（工单 0087） ----

    @Test
    @DisplayName("金额准入：未配置硬线恒放行；已用 ≥ 硬线拒绝")
    public void testCostAdmitLines() {
        stubUsageRepository();
        GovernancePrincipal principal = principalWithBudget(null, null);
        // 未配置 cost 硬线（库中无列值）
        VirtualKeyVO noCost = VirtualKeyVO.builder().id(42L).keyName("k").build();
        when(repository.findById(42L)).thenReturn(noCost);
        assertTrue(service.admitCost(principal).allowed());

        VirtualKeyVO costed = VirtualKeyVO.builder().id(42L).keyName("k")
                .costSoftLimit(new java.math.BigDecimal("10"))
                .costHardLimit(new java.math.BigDecimal("100")).build();
        when(repository.findById(42L)).thenReturn(costed);
        when(usageRepository.sumCostSince(anyLong(), any(Date.class)))
                .thenReturn(new java.math.BigDecimal("99.999"));
        assertTrue(service.admitCost(principal).allowed(), "已用 99.999 < 100 应放行");
        when(usageRepository.sumCostSince(anyLong(), any(Date.class)))
                .thenReturn(new java.math.BigDecimal("100.000"));
        assertFalse(service.admitCost(principal).allowed(), "已用 ≥ 100 应拒绝");
    }

    @Test
    @DisplayName("金额上报：越过软线发 COST_SOFT_CROSSED 事件并返回告警；未越过静默")
    public void testCostReportSoftLine() {
        stubUsageRepository();
        GovernancePrincipal principal = principalWithBudget(null, null);
        VirtualKeyVO costed = VirtualKeyVO.builder().id(42L).keyName("k")
                .costSoftLimit(new java.math.BigDecimal("10"))
                .costHardLimit(new java.math.BigDecimal("100")).build();
        when(repository.findById(42L)).thenReturn(costed);
        when(usageRepository.sumCostSince(anyLong(), any(Date.class)))
                .thenReturn(new java.math.BigDecimal("8"));

        assertFalse(service.reportCost(principal, new java.math.BigDecimal("1.5")), "8+1.5 < 10 未越过软线");
        verify(eventPublisher, never()).publish(anyString(), any());

        assertTrue(service.reportCost(principal, new java.math.BigDecimal("2.5")), "8+2.5 ≥ 10 越过软线");
        verify(eventPublisher).publish(eq(BudgetService.EVENT_COST_SOFT_CROSSED), any(Map.class));
        verify(eventPublisher, never()).publish(eq(BudgetService.EVENT_BUDGET_SOFT_CROSSED), any(Map.class));
    }

    @Test
    @DisplayName("金额上报：未配置软线/账本异常不阻断（返回 false）")
    public void testCostReportTolerance() {
        stubUsageRepository();
        GovernancePrincipal principal = principalWithBudget(null, null);
        when(repository.findById(42L)).thenReturn(VirtualKeyVO.builder().id(42L).keyName("k").build());
        assertFalse(service.reportCost(principal, new java.math.BigDecimal("5")));

        when(repository.findById(42L)).thenThrow(new RuntimeException("db down"));
        assertFalse(service.reportCost(principal, new java.math.BigDecimal("5")));
    }

    @Test
    @DisplayName("未启用预算 — 直通零开销（不查库）")
    public void testPassthroughWhenNoBudget() {
        assertTrue(service.admit(null).allowed());
        assertTrue(service.admit(GovernancePrincipal.anonymous()).allowed());
        assertTrue(service.admit(principalWithBudget(null, null)).allowed());
        verifyNoInteractions(repository);
    }

    // ---- 滚动窗口配额（工单 0158） ----

    @Test
    @DisplayName("滚动窗口 — 硬线拒绝（-32014 口径），账本派生计数、零惰性重置零计数列写入")
    public void testSlidingWindowHardLine() {
        stubUsageRepository();
        VirtualKeyVO sliding = VirtualKeyVO.builder().id(42L).keyName("k")
                .budgetHard(5L).budgetWindowType("DAY").build();
        when(repository.findById(42L)).thenReturn(sliding);
        when(usageRepository.countSince(eq(42L), any(Date.class))).thenReturn(5L);
        when(usageRepository.sumTokensSince(eq(42L), any(Date.class))).thenReturn(1234L);

        IBudgetService.BudgetVerdict verdict = service.admit(principalWithBudget(null, 5L));

        assertFalse(verdict.allowed(), "已用 5 + 本次 1 > 硬线 5 → 拒绝（-32014 口径）");
        // 滑动窗口不触碰固定窗口计数列
        verify(repository, never()).resetBudgetWindow(anyLong());
        verify(repository, never()).incrementBudgetUsed(anyLong());
        // 窗口起点 = now-24h（回溯口径，ArgumentCaptor 校验边界）
        org.mockito.ArgumentCaptor<Date> sinceCaptor = org.mockito.ArgumentCaptor.forClass(Date.class);
        verify(usageRepository).countSince(eq(42L), sinceCaptor.capture());
        long expectedStart = System.currentTimeMillis() - 24 * 3600_000L;
        assertTrue(Math.abs(sinceCaptor.getValue().getTime() - expectedStart) < 60_000,
                "日窗起点应为 now-24h（跨窗口边界回溯）");
    }

    @Test
    @DisplayName("滚动窗口 — 软线事件（含 window/windowTokens），放行且不写计数列")
    public void testSlidingWindowSoftLine() {
        stubUsageRepository();
        VirtualKeyVO sliding = VirtualKeyVO.builder().id(42L).keyName("k")
                .budgetSoft(4L).budgetHard(10L).budgetWindowType("WEEK").build();
        when(repository.findById(42L)).thenReturn(sliding);
        when(usageRepository.countSince(eq(42L), any(Date.class))).thenReturn(3L);
        when(usageRepository.sumTokensSince(eq(42L), any(Date.class))).thenReturn(88L);

        IBudgetService.BudgetVerdict verdict = service.admit(principalWithBudget(4L, 10L));

        assertTrue(verdict.allowed());
        assertTrue(verdict.softWarning(), "3+1 >= 软线 4 → 告警");
        verify(repository, never()).incrementBudgetUsed(anyLong());
        verify(eventPublisher).publish(eq(BudgetService.EVENT_BUDGET_SOFT_CROSSED), argThat(payload ->
                Long.valueOf(4L).equals(payload.get("used"))
                        && "WEEK".equals(payload.get("window"))
                        && Long.valueOf(88L).equals(payload.get("windowTokens"))));
    }

    @Test
    @DisplayName("存量固定窗口兼容 — 未配置窗口类型走旧行为（惰性重置+计数列），不触账本 countSince")
    public void testLegacyFixedWindowUnchanged() {
        // 不接账本（usageRepositoryProvider 缺席也不影响旧路径）
        when(repository.findById(42L)).thenReturn(VirtualKeyVO.builder()
                .id(42L).keyName("k").budgetHard(10L).budgetUsed(1L)
                .budgetResetAt(new Date(System.currentTimeMillis() + 3600_000)).build());

        IBudgetService.BudgetVerdict verdict = service.admit(principalWithBudget(null, 10L));

        assertTrue(verdict.allowed());
        verify(repository).incrementBudgetUsed(42L);
        verify(usageRepository, never()).countSince(anyLong(), any(Date.class));
    }

    @Test
    @DisplayName("滚动窗口 — 金额口径 admitCost 按 DAY/WEEK/MONTH 回溯取 sumCostSince")
    public void testSlidingCostWindow() {
        stubUsageRepository();
        VirtualKeyVO sliding = VirtualKeyVO.builder().id(42L).keyName("k")
                .costSoftLimit(new java.math.BigDecimal("10"))
                .costHardLimit(new java.math.BigDecimal("100"))
                .budgetWindowType("MONTH").build();
        when(repository.findById(42L)).thenReturn(sliding);
        when(usageRepository.sumCostSince(eq(42L), any(Date.class)))
                .thenReturn(new java.math.BigDecimal("99.5"));

        assertTrue(service.admitCost(principalWithBudget(null, null)).allowed(), "99.5 < 100 放行");
        when(usageRepository.sumCostSince(eq(42L), any(Date.class)))
                .thenReturn(new java.math.BigDecimal("100"));
        assertFalse(service.admitCost(principalWithBudget(null, null)).allowed(), "已用 ≥ 100 拒绝");
        // 月窗起点 = 自然月回溯（约 30 天内，含月长不一）
        org.mockito.ArgumentCaptor<Date> sinceCaptor = org.mockito.ArgumentCaptor.forClass(Date.class);
        verify(usageRepository, org.mockito.Mockito.times(2)).sumCostSince(eq(42L), sinceCaptor.capture());
        long expectedStart = System.currentTimeMillis() - 32L * 24 * 3600_000L;
        assertTrue(sinceCaptor.getAllValues().get(0).getTime() > expectedStart, "月窗起点应晚于 now-32d（自然月回溯）");
    }

    @Test
    @DisplayName("硬线 — used+1 超过硬线拒绝且不计数")
    public void testHardLineDeny() {
        when(repository.findById(42L)).thenReturn(VirtualKeyVO.builder()
                .id(42L).status("ACTIVE").budgetHard(10L).budgetUsed(10L)
                .budgetResetAt(new Date(System.currentTimeMillis() + 3600_000)).build());

        IBudgetService.BudgetVerdict verdict = service.admit(principalWithBudget(8L, 10L));

        assertFalse(verdict.allowed());
        verify(repository, never()).incrementBudgetUsed(anyLong());
    }

    @Test
    @DisplayName("软线 — 越过软线放行 + 告警头标记 + 事件发布")
    public void testSoftLineWarningAndEvent() {
        when(repository.findById(42L)).thenReturn(VirtualKeyVO.builder()
                .id(42L).keyName("k").status("ACTIVE")
                .budgetSoft(8L).budgetHard(10L).budgetUsed(7L)
                .budgetResetAt(new Date(System.currentTimeMillis() + 3600_000)).build());

        IBudgetService.BudgetVerdict verdict = service.admit(principalWithBudget(8L, 10L));

        assertTrue(verdict.allowed());
        assertTrue(verdict.softWarning(), "used+1=8 达软线");
        verify(repository).incrementBudgetUsed(42L);
        verify(eventPublisher).publish(eq(BudgetService.EVENT_BUDGET_SOFT_CROSSED), anyMap());
    }

    @Test
    @DisplayName("窗口惰性重置 — reset_at 过期即清零顺延，重置后从 1 计数")
    public void testLazyWindowReset() {
        when(repository.findById(42L)).thenReturn(VirtualKeyVO.builder()
                .id(42L).status("ACTIVE")
                .budgetSoft(5L).budgetHard(10L).budgetUsed(9L)
                .budgetDurationHours(720)
                .budgetResetAt(new Date(System.currentTimeMillis() - 1_000)).build());

        IBudgetService.BudgetVerdict verdict = service.admit(principalWithBudget(5L, 10L));

        verify(repository).resetBudgetWindow(42L);
        assertTrue(verdict.allowed(), "重置后 used=0，本次为 1");
        assertFalse(verdict.softWarning());
        verify(repository).incrementBudgetUsed(42L);
    }

    @Test
    @DisplayName("计数失败 — 放行不阻断主链")
    public void testIncrementFailureTolerated() {
        when(repository.findById(42L)).thenReturn(VirtualKeyVO.builder()
                .id(42L).status("ACTIVE").budgetHard(10L).budgetUsed(1L)
                .budgetResetAt(new Date(System.currentTimeMillis() + 3600_000)).build());
        doThrow(new RuntimeException("db down")).when(repository).incrementBudgetUsed(anyLong());

        assertTrue(service.admit(principalWithBudget(null, 10L)).allowed());
    }

    @Test
    @DisplayName("事件发布失败 — 不影响判决")
    public void testEventFailureTolerated() {
        when(repository.findById(42L)).thenReturn(VirtualKeyVO.builder()
                .id(42L).keyName("k").status("ACTIVE")
                .budgetSoft(1L).budgetHard(10L).budgetUsed(0L)
                .budgetResetAt(new Date(System.currentTimeMillis() + 3600_000)).build());
        doThrow(new RuntimeException("publish down")).when(eventPublisher).publish(anyString(), any());

        IBudgetService.BudgetVerdict verdict = service.admit(principalWithBudget(1L, 10L));
        assertTrue(verdict.allowed() && verdict.softWarning());
    }

    private static <V> Map<String, V> anyMap() {
        return any();
    }

    @Test
    @DisplayName("临时提额（0052）— 未过期生效硬线取较大值；过期惰性回落")
    void testEffectiveHardWithTempBudget() {
        VirtualKeyVO vo = VirtualKeyVO.builder()
                .id(42L).status("ACTIVE").budgetHard(1000L).budgetUsed(999L)
                .tempBudgetHard(1500L)
                .tempBudgetExpires(new Date(System.currentTimeMillis() + 3600_000))
                .budgetResetAt(new Date(System.currentTimeMillis() + 3600_000))
                .build();
        when(repository.findById(42L)).thenReturn(vo);

        IBudgetService.BudgetVerdict verdict = service.admit(principalWithBudget(null, 1000L));
        assertTrue(verdict.allowed(), "生效硬线 1500，used+1=1000 未超");
        assertEquals(1500L, verdict.hard());

        VirtualKeyVO expired = VirtualKeyVO.builder()
                .id(42L).status("ACTIVE").budgetHard(1000L).budgetUsed(1000L)
                .tempBudgetHard(1500L)
                .tempBudgetExpires(new Date(System.currentTimeMillis() - 1000))
                .budgetResetAt(new Date(System.currentTimeMillis() + 3600_000))
                .build();
        when(repository.findById(42L)).thenReturn(expired);
        assertFalse(service.admit(principalWithBudget(null, 1000L)).allowed(), "临时提额过期回落 1000，used+1 超");
    }
}

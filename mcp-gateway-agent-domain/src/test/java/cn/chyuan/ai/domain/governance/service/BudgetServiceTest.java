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

    @InjectMocks
    private BudgetService service;

    private GovernancePrincipal principalWithBudget(Long soft, Long hard) {
        return GovernancePrincipal.builder()
                .authType(GovernancePrincipal.AuthType.VIRTUAL_KEY)
                .virtualKeyId(42L)
                .budgetSoft(soft)
                .budgetHard(hard)
                .build();
    }

    @Test
    @DisplayName("未启用预算 — 直通零开销（不查库）")
    public void testPassthroughWhenNoBudget() {
        assertTrue(service.admit(null).allowed());
        assertTrue(service.admit(GovernancePrincipal.anonymous()).allowed());
        assertTrue(service.admit(principalWithBudget(null, null)).allowed());
        verifyNoInteractions(repository);
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
}

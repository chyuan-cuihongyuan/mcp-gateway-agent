package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 每密钥并发闸测试（工单 0056）
 */
@DisplayName("并发闸测试")
public class ConcurrencyGuardServiceTest {

    private ConcurrencyGuardService serviceWithLimit(int limit) {
        ConcurrencyGuardService service = new ConcurrencyGuardService();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "maxConcurrentPerKey", limit);
        return service;
    }

    private GovernancePrincipal principal(long keyId) {
        return GovernancePrincipal.builder()
                .authType(GovernancePrincipal.AuthType.VIRTUAL_KEY)
                .virtualKeyId(keyId)
                .build();
    }

    @Test
    @DisplayName("限值 0 — 不限（现状兼容，null 主体直通）")
    public void testUnlimitedByDefault() {
        ConcurrencyGuardService service = serviceWithLimit(0);
        assertTrue(service.tryAcquire(principal(1L)));
        assertTrue(service.tryAcquire(null));
    }

    @Test
    @DisplayName("占位/释放 — 超限拒绝，释放后可再占")
    public void testAcquireReleaseCycle() {
        ConcurrencyGuardService service = serviceWithLimit(2);
        GovernancePrincipal p = principal(7L);

        assertTrue(service.tryAcquire(p));
        assertTrue(service.tryAcquire(p));
        assertFalse(service.tryAcquire(p), "第三个占位应被拒");
        assertEquals(2, service.inFlightOf(7L));

        service.release(p);
        assertTrue(service.tryAcquire(p), "释放后可再占");
    }

    @Test
    @DisplayName("密钥隔离 — 不同 keyId 互不影响")
    public void testKeyIsolation() {
        ConcurrencyGuardService service = serviceWithLimit(1);
        assertTrue(service.tryAcquire(principal(1L)));
        assertTrue(service.tryAcquire(principal(2L)), "另一把钥匙不受影响");
        assertFalse(service.tryAcquire(principal(1L)));
    }

    @Test
    @DisplayName("释放幂等 — 计数不穿底为负")
    public void testReleaseIdempotent() {
        ConcurrencyGuardService service = serviceWithLimit(2);
        GovernancePrincipal p = principal(9L);
        service.tryAcquire(p);
        service.release(p);
        service.release(p);
        assertEquals(0, service.inFlightOf(9L));
    }
}

package cn.chyuan.ai.trigger.filter;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import cn.chyuan.ai.domain.governance.service.IQuotaService;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import cn.chyuan.ai.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 配额限流过滤器测试（工单 0019 验收：429 含额度信息 / 503 fail-closed / 直通）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("配额限流过滤器测试")
public class QuotaEnforcementFilterTest {

    @Mock
    private IQuotaService quotaService;

    private QuotaEnforcementFilter filter;

    private final GovernancePrincipal principal = GovernancePrincipal.builder()
            .authType(GovernancePrincipal.AuthType.VIRTUAL_KEY)
            .virtualKeyId(42L)
            .build();

    @BeforeEach
    public void setUp() {
        filter = new QuotaEnforcementFilter(quotaService, null);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/api-gateway/gateway_001/mcp/sse");
        request.setAttribute(GovernanceAuthFilter.PRINCIPAL_ATTR, principal);
        return request;
    }

    @Test
    @DisplayName("超配额 — HTTP 429 + JSON-RPC -32009，含剩余额度与重试秒数，Retry-After 头")
    public void testQuotaExceeded_Returns429WithQuotaInfo() throws Exception {
        when(quotaService.checkAndConsume("gateway_001", principal))
                .thenReturn(new IQuotaService.QuotaVerdict(false, true, 0, 30));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request(), response, chain);

        assertEquals(429, response.getStatus());
        assertEquals("30", response.getHeader("Retry-After"));
        String body = response.getContentAsString();
        assertTrue(body.contains("\"code\":" + McpErrorCodes.QUOTA_EXCEEDED), body);
        assertTrue(body.contains("\"remaining\":0"), "应含剩余额度");
        assertTrue(body.contains("\"retryAfterSeconds\":30"), "应含重试秒数");
        assertNull(chain.getRequest(), "拒绝请求不应进入链");
    }

    @Test
    @DisplayName("Redis 故障 — HTTP 503 + -32010 fail-closed（不降级放行）")
    public void testBackendFailure_Returns503FailClosed() throws Exception {
        when(quotaService.checkAndConsume("gateway_001", principal))
                .thenThrow(new AppException(McpErrorCodes.QUOTA_SERVICE_UNAVAILABLE,
                        "配额服务暂不可用，请稍后重试（fail-closed）"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request(), response, chain);

        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"code\":" + McpErrorCodes.QUOTA_SERVICE_UNAVAILABLE));
        assertNull(chain.getRequest(), "fail-closed 不放行");
    }

    @Test
    @DisplayName("配额内 — 放行进入后续链")
    public void testWithinQuota_PassesThrough() throws Exception {
        when(quotaService.checkAndConsume("gateway_001", principal))
                .thenReturn(new IQuotaService.QuotaVerdict(true, true, 59, 0));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request(), response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    @DisplayName("无认证主体 — 不适用配额直通（开放网关匿名路径）")
    public void testNoPrincipal_SkipsQuota() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/api-gateway/gateway_001/mcp/sse");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
        org.mockito.Mockito.verifyNoInteractions(quotaService);
    }
}

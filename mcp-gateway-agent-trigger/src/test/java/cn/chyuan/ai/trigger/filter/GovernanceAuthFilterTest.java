package cn.chyuan.ai.trigger.filter;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import cn.chyuan.ai.domain.governance.service.IGovernanceAuthService;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 统一认证过滤器协议级测试（工单 0017 验收：无凭证 401 / 错钥匙 403 / 有效放行）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("治理面统一认证过滤器测试")
public class GovernanceAuthFilterTest {

    @Mock
    private IGovernanceAuthService governanceAuthService;

    private GovernanceAuthFilter filter;

    @BeforeEach
    public void setUp() {
        filter = new GovernanceAuthFilter(governanceAuthService);
    }

    @Test
    @DisplayName("无凭证 → HTTP 401 + JSON-RPC 结构化错误（AUTH_REQUIRED）")
    public void testMissingCredential_Returns401() throws Exception {
        // 准备 — 强校验网关 + 无凭证
        when(governanceAuthService.authenticate(eq("gateway_001"), isNull(), any()))
                .thenThrow(new AppException(McpErrorCodes.AUTH_REQUIRED, "缺少调用凭证"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api-gateway/gateway_001/mcp/sse");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        // 执行
        filter.doFilter(request, response, chain);

        // 验证
        assertEquals(401, response.getStatus(), "无凭证应返回 401");
        assertTrue(response.getContentAsString().contains("\"code\":" + McpErrorCodes.AUTH_REQUIRED),
                "应包含 JSON-RPC 错误码");
        assertTrue(response.getContentAsString().contains("jsonrpc"), "应为 JSON-RPC 结构化错误");
        assertNull(chain.getRequest(), "请求不应进入后续链");
    }

    @Test
    @DisplayName("无效凭证 → HTTP 403 + JSON-RPC 结构化错误（INSUFFICIENT_PERMISSIONS）")
    public void testInvalidCredential_Returns403() throws Exception {
        // 准备 — 强校验网关 + 错误密钥
        when(governanceAuthService.authenticate(eq("gateway_001"), eq("vk-invalid"), any()))
                .thenThrow(new AppException(McpErrorCodes.INSUFFICIENT_PERMISSIONS, "凭证无效或已吊销"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api-gateway/gateway_001/mcp/sse");
        request.setParameter("api_key", "vk-invalid");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        // 执行
        filter.doFilter(request, response, chain);

        // 验证
        assertEquals(403, response.getStatus(), "错钥匙应返回 403");
        assertTrue(response.getContentAsString().contains("\"code\":" + McpErrorCodes.INSUFFICIENT_PERMISSIONS));
        assertNull(chain.getRequest());
    }

    @Test
    @DisplayName("有效 vk- 密钥 → 放行并写入认证主体属性")
    public void testValidCredential_PassesAndSetsPrincipal() throws Exception {
        // 准备
        GovernancePrincipal principal = GovernancePrincipal.builder()
                .authType(GovernancePrincipal.AuthType.VIRTUAL_KEY)
                .virtualKeyId(1L)
                .apiKeyHash("abc123")
                .build();
        when(governanceAuthService.authenticate(eq("gateway_001"), eq("vk-valid"), any())).thenReturn(principal);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api-gateway/gateway_001/mcp/sse");
        request.setParameter("api_key", "vk-valid");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        // 执行
        filter.doFilter(request, response, chain);

        // 验证 — 进入后续链 + 主体属性
        assertNotNull(chain.getRequest(), "有效凭证应进入后续过滤器链");
        assertSame(principal, request.getAttribute(GovernanceAuthFilter.PRINCIPAL_ATTR),
                "认证主体应写入请求属性");
        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("Bearer JWT → 以去前缀后的 token 认证")
    public void testBearerJwt_DelegatesWithoutPrefix() throws Exception {
        // 准备
        when(governanceAuthService.authenticate(eq("gateway_001"), eq("Bearer eyJhbGciOiJ"), any()))
                .thenReturn(GovernancePrincipal.builder().authType(GovernancePrincipal.AuthType.JWT).build());

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api-gateway/gateway_001/mcp/sse");
        request.addHeader("Authorization", "Bearer eyJhbGciOiJ");
        MockFilterChain chain = new MockFilterChain();

        // 执行
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        // 验证 — 完整 Bearer 串透传（前缀剥离由 domain 服务处理）
        verify(governanceAuthService).authenticate(eq("gateway_001"), eq("Bearer eyJhbGciOiJ"), any());
        assertNotNull(chain.getRequest());
    }

    @Test
    @DisplayName("CORS 预检（OPTIONS）→ 直接放行不做认证")
    public void testOptionsPreflight_BypassesAuth() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api-gateway/gateway_001/mcp/sse");
        MockFilterChain chain = new MockFilterChain();

        // 执行
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        // 验证
        assertNotNull(chain.getRequest(), "预检请求应放行");
        verifyNoInteractions(governanceAuthService);
    }
}

package cn.chyuan.ai.trigger.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AdminAuthInterceptor 三态测试（SELFLOOP6 loop-669，工单 0930/0931）。
 * fail-closed 语义：未配置 token 时一律拒绝。
 */
class AdminAuthInterceptorTest {

    private AdminAuthInterceptor interceptor;
    private HttpServletRequest request;
    private HttpServletResponse response;

    @BeforeEach
    void setUp() {
        interceptor = new AdminAuthInterceptor();
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
    }

    private void withToken(String configured) {
        ReflectionTestUtils.setField(interceptor, "expectedToken", configured);
    }

    @Test
    @DisplayName("fail-closed：token 未配置 → 一律 401 拒绝")
    void rejectsWhenTokenUnconfigured() throws Exception {
        withToken("");
        when(request.getRequestURI()).thenReturn("/admin/save_gateway_config");
        assertFalse(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    @DisplayName("token 错误 → 拒绝")
    void rejectsWrongToken() throws Exception {
        withToken("secret");
        when(request.getHeader(AdminAuthInterceptor.HEADER_ADMIN_TOKEN)).thenReturn("wrong");
        assertFalse(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    @DisplayName("token 正确 → 放行")
    void acceptsCorrectToken() throws Exception {
        withToken("secret");
        when(request.getHeader(AdminAuthInterceptor.HEADER_ADMIN_TOKEN)).thenReturn("secret");
        assertTrue(interceptor.preHandle(request, response, new Object()));
    }
}

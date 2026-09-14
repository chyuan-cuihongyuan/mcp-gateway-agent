package cn.chyuan.ai.trigger.config;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 安全响应头基线（SELFLOOP3 loop-323，工单 0444/0445）
 */
class SecurityHeadersFilterTest {

    @Test
    void baselineHeadersAreSetOnEveryResponse() throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new SecurityHeadersFilter().doFilter(
                new MockHttpServletRequest(), response, new MockFilterChain());

        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
    }
}

package cn.chyuan.ai.trigger.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 安全响应头过滤器（SELFLOOP3 loop-323，工单 0444/0445；E2 注入 H2）
 *
 * <p>OWASP secure headers 基线起步集：nosniff（MIME 嗅探防护）、DENY（点击劫持
 * 防护）、referrer-policy（跨源 referrer 最小泄露）。HSTS 待 TLS 终结面就绪后启用。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        filterChain.doFilter(request, response);
    }
}

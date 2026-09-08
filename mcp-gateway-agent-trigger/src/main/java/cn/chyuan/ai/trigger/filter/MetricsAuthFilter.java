package cn.chyuan.ai.trigger.filter;

import cn.chyuan.ai.domain.governance.adapter.codec.IJwtCodec;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import cn.chyuan.ai.types.exception.AppException;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;

/**
 * /actuator/prometheus 认证开关（工单 0067/0070，LiteLLM require_auth_for_metrics_endpoint 口径）
 *
 * <p>仅当 governance.metrics.auth-required=true 时注册（条件装配于 GovernanceFilterConfig）：
 * 要求 admin JWT（Bearer），校验失败 401/403；关闭态（默认，内网口径）匿名可达；
 * /health 不在本过滤器管辖（保持匿名）。
 *
 * @author chyuan
 */
@Slf4j
public class MetricsAuthFilter implements Filter {

    private final IJwtCodec jwtCodec;

    public MetricsAuthFilter(IJwtCodec jwtCodec) {
        this.jwtCodec = jwtCodec;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest) || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }
        String authorization = httpRequest.getHeader("Authorization");
        if (StringUtils.isBlank(authorization) || !authorization.startsWith("Bearer ")) {
            httpResponse.setStatus(401);
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write("{\"error\":\"metrics endpoint requires admin JWT\"}");
            return;
        }
        try {
            jwtCodec.verify(authorization.substring("Bearer ".length()));
        } catch (AppException e) {
            httpResponse.setStatus(403);
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write("{\"error\":\"invalid admin JWT\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}

package cn.chyuan.ai.trigger.filter;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import cn.chyuan.ai.domain.governance.service.IGovernanceAuthService;
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
 * 全局流量面认证过滤器（工单 0063：/v1/* OpenAI 兼容面；A2A 面 0066 复用）
 *
 * <p>与网关面认证的差异：无网关维度——不做网关强校验开关与网关授权判定，
 * 凭证有效性（vk 状态/过期/宽限/IP 白名单）与 JWT 全量生效；
 * 主体写入同一请求属性供 CEL/配额/预算/账本消费。
 * 错误形态对齐 OpenAI 风格（{"error":{"message","type"}}）。
 *
 * @author chyuan
 */
@Slf4j
public class GlobalTrafficAuthFilter implements Filter {

    private final IGovernanceAuthService governanceAuthService;

    public GlobalTrafficAuthFilter(IGovernanceAuthService governanceAuthService) {
        this.governanceAuthService = governanceAuthService;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest) || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }
        if ("OPTIONS".equalsIgnoreCase(httpRequest.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String credential = resolveCredential(httpRequest);
        try {
            GovernancePrincipal principal = governanceAuthService.authenticateGlobal(credential, clientIp(httpRequest));
            httpRequest.setAttribute(GovernancePrincipal.REQUEST_ATTR, principal);
            chain.doFilter(request, response);
        } catch (AppException e) {
            boolean authRequired = String.valueOf(McpErrorCodes.AUTH_REQUIRED).equals(e.getCode());
            int httpStatus = authRequired ? 401 : 403;
            log.warn("全局流量面认证拒绝 path={} reason={}", httpRequest.getRequestURI(), e.getInfo());
            writeOpenAiError(httpResponse, httpStatus, e.getInfo());
        }
    }

    private String resolveCredential(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (StringUtils.isNotBlank(authorization)) {
            return authorization;
        }
        return request.getParameter("api_key");
    }

    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (StringUtils.isNotBlank(xff)) {
            int comma = xff.indexOf(',');
            String first = comma > 0 ? xff.substring(0, comma) : xff;
            if (StringUtils.isNotBlank(first)) {
                return first.trim();
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        return StringUtils.isNotBlank(realIp) ? realIp.trim() : request.getRemoteAddr();
    }

    /** OpenAI 风格错误体 */
    static void writeOpenAiError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":{\"message\":" + quote(message)
                + ",\"type\":\"" + (status == 401 ? "invalid_request_error" : "permission_error") + "\"}}");
        response.getWriter().flush();
    }

    private static String quote(String value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}

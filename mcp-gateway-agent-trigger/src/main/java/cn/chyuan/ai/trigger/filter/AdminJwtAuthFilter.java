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
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * admin 控制台 JWT 过滤器（工单 0017：admin 不再裸奔）
 *
 * <p>拦截 /admin/*（登录接口除外）：缺 token/无效 401；
 * 只读角色（READONLY）写方法 403；主体角色写入请求属性 {@link #ADMIN_ROLE_ATTR}。
 *
 * @author chyuan
 */
@Slf4j
public class AdminJwtAuthFilter implements Filter {

    public static final String ADMIN_ROLE_ATTR = "GOVERNANCE_ADMIN_ROLE";

    /** 登录白名单（无需 JWT） */
    private static final String LOGIN_PATH = "/admin/v1/auth/login";

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_READONLY = "READONLY";

    private static final String BEARER_PREFIX = "Bearer ";

    private final IJwtCodec jwtCodec;

    public AdminJwtAuthFilter(IJwtCodec jwtCodec) {
        this.jwtCodec = jwtCodec;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest) || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }

        String path = httpRequest.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(httpRequest.getMethod()) || LOGIN_PATH.equals(path)) {
            chain.doFilter(request, response);
            return;
        }

        String authorization = httpRequest.getHeader("Authorization");
        if (StringUtils.isBlank(authorization) || !authorization.startsWith(BEARER_PREFIX)) {
            writeError(httpResponse, 401, McpErrorCodes.AUTH_REQUIRED, "缺少 Bearer JWT 登录凭证");
            return;
        }

        IJwtCodec.JwtClaims claims;
        try {
            claims = jwtCodec.verify(authorization.substring(BEARER_PREFIX.length()).trim());
        } catch (AppException e) {
            writeError(httpResponse, 401, McpErrorCodes.AUTH_REQUIRED, "JWT 无效或已过期：" + e.getInfo());
            return;
        }

        String role = firstRole(claims.roles());
        httpRequest.setAttribute(ADMIN_ROLE_ATTR, role);

        // 只读角色：仅放行读方法（0011 决议：READONLY 仅查）
        boolean readOnly = ROLE_READONLY.equals(role);
        String method = httpRequest.getMethod().toUpperCase();
        if (readOnly && !("GET".equals(method) || "HEAD".equals(method))) {
            writeError(httpResponse, 403, McpErrorCodes.INSUFFICIENT_PERMISSIONS, "只读角色无权执行写操作：" + method);
            return;
        }

        chain.doFilter(request, response);
    }

    private String firstRole(List<String> roles) {
        return roles == null || roles.isEmpty() ? ROLE_READONLY : roles.get(0);
    }

    private void writeError(HttpServletResponse response, int httpStatus, int code, String message) throws IOException {
        response.setStatus(httpStatus);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String body = "{\"code\":\"" + (httpStatus == 401 ? "0001" : "0002") + "\",\"info\":\"" + message + "\"}";
        response.getWriter().write(body);
        response.getWriter().flush();
    }
}

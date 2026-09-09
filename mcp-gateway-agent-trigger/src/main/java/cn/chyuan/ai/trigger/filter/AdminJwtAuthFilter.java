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
 * admin 控制台 JWT 过滤器（工单 0017；0109 升级四角色端点矩阵）
 *
 * <p>拦截 /admin/*（登录接口除外）：缺 token/无效 401；主体角色写入请求属性
 * {@link #ADMIN_ROLE_ATTR}；按「端点组 → 最低角色」矩阵鉴权，越权 403（附所需角色）。
 *
 * <p>四角色（权限从高到低）：SUPER_ADMIN（账户/系统配置/导入导出）→ ADMIN（治理读写）
 * → AUDITOR（读 + 审计/用量 + 审计导出）→ READONLY（读）。矩阵见 docs/03/19。
 * 兼容红线：无 role 兜底 READONLY 的既有语义不变。
 *
 * @author chyuan
 */
@Slf4j
public class AdminJwtAuthFilter implements Filter {

    public static final String ADMIN_ROLE_ATTR = "GOVERNANCE_ADMIN_ROLE";

    /** 登录白名单（无需 JWT） */
    private static final String LOGIN_PATH = "/admin/v1/auth/login";

    public static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_AUDITOR = "AUDITOR";
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

        String requiredRole = minRoleFor(path, httpRequest.getMethod().toUpperCase());
        if (roleRank(role) < roleRank(requiredRole)) {
            writeError(httpResponse, 403, McpErrorCodes.INSUFFICIENT_PERMISSIONS,
                    "角色 " + role + " 无权访问该端点（需要 " + requiredRole + " 或更高）");
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * 端点组 → 最低角色矩阵（工单 0109；docs/03/19 同步维护）：
     * 用户管理（M2）与配置导入 = 超管；配置导出 = 审计员起；用量查询 = 审计员起；
     * 其余读 = 只读可达、写 = 管理员。
     */
    static String minRoleFor(String path, String method) {
        if (path.startsWith("/admin/v1/users")) {
            return ROLE_SUPER_ADMIN;
        }
        if (path.startsWith("/admin/v1/config")) {
            // 导出（GET）审计员起；导入（POST）超管
            return "GET".equals(method) || "HEAD".equals(method) ? ROLE_AUDITOR : ROLE_SUPER_ADMIN;
        }
        if (path.startsWith("/admin/v1/usage")) {
            return ROLE_AUDITOR;
        }
        boolean readOnlyMethod = "GET".equals(method) || "HEAD".equals(method);
        return readOnlyMethod ? ROLE_READONLY : ROLE_ADMIN;
    }

    /** 角色权限秩（越大越高；未知角色按最低处理） */
    static int roleRank(String role) {
        return switch (role == null ? "" : role) {
            case ROLE_SUPER_ADMIN -> 4;
            case ROLE_ADMIN -> 3;
            case ROLE_AUDITOR -> 2;
            case ROLE_READONLY -> 1;
            default -> 1;
        };
    }

    private String firstRole(List<String> roles) {
        // 多角色取最高秩（兼容单角色口径）
        if (roles == null || roles.isEmpty()) {
            return ROLE_READONLY;
        }
        String best = roles.get(0);
        for (String role : roles) {
            if (roleRank(role) > roleRank(best)) {
                best = role;
            }
        }
        return best;
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

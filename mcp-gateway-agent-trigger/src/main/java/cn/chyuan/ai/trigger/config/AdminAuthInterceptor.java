package cn.chyuan.ai.trigger.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理面鉴权拦截器（SELFLOOP6 loop-669）。
 * <p>
 * /admin/** 承载网关配置/认证/协议导入等管理写操作，此前完全裸奔
 * （无任何鉴权层）。对齐 obs AuthKeyInterceptor 的 fail-closed 惯例：
 * admin.auth-token 未配置时拒绝全部管理请求（缺配置=拒绝，而非放行）。
 */
@Slf4j
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    public static final String HEADER_ADMIN_TOKEN = "X-Admin-Token";

    @Value("${admin.auth-token:}")
    private String expectedToken;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (expectedToken == null || expectedToken.isEmpty()) {
            log.warn("admin.auth-token 未配置，拒绝管理请求, uri={}, remote={}",
                    request.getRequestURI(), request.getRemoteAddr());
            response.setStatus(401);
            return false;
        }
        String token = request.getHeader(HEADER_ADMIN_TOKEN);
        if (expectedToken.equals(token)) {
            return true;
        }
        log.warn("admin token 校验失败, uri={}, remote={}", request.getRequestURI(), request.getRemoteAddr());
        response.setStatus(401);
        return false;
    }
}

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
 * 统一认证过滤器（工单 0017 / 0011 认证流程）
 *
 * <p>拦截 /api-gateway/{gatewayId}/mcp/**（SSE 连接与消息上报）：
 * 无凭证 401、无效凭证/未授权 403（JSON-RPC 结构化错误）；
 * 成功后主体写入请求属性 {@link #PRINCIPAL_ATTR} 供控制器与 CEL（0018）消费。
 * 凭证来源：Authorization: Bearer &lt;JWT&gt; 或 api_key 查询参数（兼容存量客户端）。
 *
 * @author chyuan
 */
@Slf4j
public class GovernanceAuthFilter implements Filter {

    public static final String PRINCIPAL_ATTR = GovernancePrincipal.REQUEST_ATTR;

    /** /api-gateway/ 后的网关 ID 路径段 */
    private static final String PATH_PREFIX = "/api-gateway/";

    private final IGovernanceAuthService governanceAuthService;

    public GovernanceAuthFilter(IGovernanceAuthService governanceAuthService) {
        this.governanceAuthService = governanceAuthService;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest) || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }

        // CORS 预检放行
        if ("OPTIONS".equalsIgnoreCase(httpRequest.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String gatewayId = extractGatewayId(httpRequest.getRequestURI());
        if (gatewayId == null) {
            // 非网关路径不处理（过滤器只注册在 /api-gateway/* 下，防御性兜底）
            chain.doFilter(request, response);
            return;
        }

        String credential = resolveCredential(httpRequest);
        try {
            GovernancePrincipal principal = governanceAuthService.authenticate(gatewayId, credential, resolveClientIp(httpRequest));
            // 请求标签（工单 0088）：成本归因维度（与全局流量面同头）
            principal.setTags(cn.chyuan.ai.types.util.TagParser.parseHeader(httpRequest.getHeader("X-Gateway-Tags")));
            httpRequest.setAttribute(PRINCIPAL_ATTR, principal);
            chain.doFilter(request, response);
        } catch (AppException e) {
            boolean authRequired = String.valueOf(McpErrorCodes.AUTH_REQUIRED).equals(e.getCode());
            int httpStatus = authRequired ? 401 : 403;
            log.warn("治理面认证拒绝 gateway:{} credentialSource:{} reason:{}", gatewayId,
                    credential == null || credential.isBlank() ? "none" : "present", e.getInfo());
            JsonRpcErrorWriter.write(httpResponse, httpStatus, Integer.parseInt(e.getCode()), e.getInfo());
        }
    }

    /** Authorization: Bearer 优先，api_key 查询参数兜底（存量客户端兼容） */
    private String resolveCredential(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (StringUtils.isNotBlank(authorization)) {
            return authorization;
        }
        return request.getParameter("api_key");
    }

    /** 来源 IP：X-Forwarded-For 首跳优先，X-Real-IP 次之，remoteAddr 兜底（工单 0045 IP 白名单） */
    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (StringUtils.isNotBlank(xff)) {
            int comma = xff.indexOf(',');
            String first = comma > 0 ? xff.substring(0, comma) : xff;
            if (StringUtils.isNotBlank(first)) {
                return first.trim();
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.isNotBlank(realIp)) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    private String extractGatewayId(String uri) {
        if (uri == null || !uri.startsWith(PATH_PREFIX)) {
            return null;
        }
        String rest = uri.substring(PATH_PREFIX.length());
        int slash = rest.indexOf('/');
        String segment = slash > 0 ? rest.substring(0, slash) : rest;
        return segment.isBlank() ? null : segment;
    }
}

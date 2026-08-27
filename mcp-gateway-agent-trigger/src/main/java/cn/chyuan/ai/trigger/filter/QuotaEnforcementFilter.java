package cn.chyuan.ai.trigger.filter;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import cn.chyuan.ai.domain.governance.service.IQuotaService;
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

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配额限流过滤器（工单 0019 / 0011 认证流程第 4 步）
 *
 * <p>注册于统一认证过滤器之后（order 11）：对 VIRTUAL_KEY 主体的每次请求
 * 执行 per-key RPM + 日请求配额（Bucket4j 多带宽同桶，Redis 跨实例）。
 * 超配额 → HTTP 429 + JSON-RPC -32009（含剩余额度与重试秒数）；
 * Redis 故障 → fail-closed：HTTP 503 + -32010（不降级放行）。
 * JWT/匿名/无主体路径不适用，直通。
 *
 * @author chyuan
 */
@Slf4j
public class QuotaEnforcementFilter implements Filter {

    private final IQuotaService quotaService;

    public QuotaEnforcementFilter(IQuotaService quotaService) {
        this.quotaService = quotaService;
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

        GovernancePrincipal principal =
                (GovernancePrincipal) httpRequest.getAttribute(GovernanceAuthFilter.PRINCIPAL_ATTR);
        if (principal == null) {
            // 认证过滤器未产出主体（开放网关匿名等）——不适用配额
            chain.doFilter(request, response);
            return;
        }

        String gatewayId = extractGatewayId(httpRequest.getRequestURI());
        try {
            IQuotaService.QuotaVerdict verdict = quotaService.checkAndConsume(gatewayId, principal);
            if (verdict.allowed()) {
                chain.doFilter(request, response);
                return;
            }
            // 429 + 剩余额度信息（0011 决议：结构化 429 含 retry-after 提示）
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("remaining", verdict.remaining());
            data.put("retryAfterSeconds", verdict.retryAfterSeconds());
            httpResponse.setHeader("Retry-After", String.valueOf(verdict.retryAfterSeconds()));
            JsonRpcErrorWriter.write(httpResponse, 429, McpErrorCodes.QUOTA_EXCEEDED,
                    "超出配额限制：剩余额度 " + verdict.remaining() + "，请 " + verdict.retryAfterSeconds()
                            + " 秒后重试",
                    data);
        } catch (AppException e) {
            if (String.valueOf(McpErrorCodes.QUOTA_SERVICE_UNAVAILABLE).equals(e.getCode())) {
                httpResponse.setHeader("Retry-After", "10");
                JsonRpcErrorWriter.write(httpResponse, 503, McpErrorCodes.QUOTA_SERVICE_UNAVAILABLE,
                        e.getInfo() + "，建议 10 秒后重试");
            } else {
                JsonRpcErrorWriter.write(httpResponse, 403, Integer.parseInt(e.getCode()), e.getInfo());
            }
        }
    }

    private String extractGatewayId(String uri) {
        if (uri == null || !uri.startsWith("/api-gateway/")) {
            return null;
        }
        String rest = uri.substring("/api-gateway/".length());
        int slash = rest.indexOf('/');
        String segment = slash > 0 ? rest.substring(0, slash) : rest;
        return segment.isBlank() ? null : segment;
    }
}

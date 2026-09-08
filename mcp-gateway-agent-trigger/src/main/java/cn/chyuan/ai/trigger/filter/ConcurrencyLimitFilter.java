package cn.chyuan.ai.trigger.filter;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import cn.chyuan.ai.domain.governance.service.ConcurrencyGuardService;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Map;

/**
 * 每密钥并发闸过滤器（工单 0056，order 12：配额/预算之后）
 *
 * <p>超并发 → HTTP 429 + JSON-RPC -32016（含限值与在途数）；
 * tryAcquire/Release 严格 try/finally 配对（异常路径也释放）。
 *
 * @author chyuan
 */
@Slf4j
public class ConcurrencyLimitFilter implements Filter {

    private final ConcurrencyGuardService guardService;

    private final int maxConcurrentPerKey;

    public ConcurrencyLimitFilter(ConcurrencyGuardService guardService, int maxConcurrentPerKey) {
        this.guardService = guardService;
        this.maxConcurrentPerKey = maxConcurrentPerKey;
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
        if (principal == null || !guardService.tryAcquire(principal)) {
            Map<String, Object> data = Map.of(
                    "limit", maxConcurrentPerKey,
                    "inFlight", guardService.inFlightOf(principal == null ? null : principal.getVirtualKeyId()));
            httpResponse.setHeader("Retry-After", "1");
            JsonRpcErrorWriter.write(httpResponse, 429, McpErrorCodes.CONCURRENCY_EXCEEDED,
                    "超出每密钥并发上限：" + maxConcurrentPerKey, data);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            guardService.release(principal);
        }
    }
}

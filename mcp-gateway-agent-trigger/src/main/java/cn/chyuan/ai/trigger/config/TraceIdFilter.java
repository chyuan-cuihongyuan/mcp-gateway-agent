package cn.chyuan.ai.trigger.config;

import cn.chyuan.ai.infrastructure.utils.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * HTTP 入口 traceId 过滤器（D08，借鉴 OTel W3C trace-context 头透传语义）
 *
 * <p>双写两处：</p>
 * <ul>
 *   <li>MDC key "trace-id"——logback 既有 pattern（%X{trace-id}）此前无写入方，本过滤器补齐；</li>
 *   <li>{@link TraceContext}（自研 ThreadLocal）——SessionPort 跨服务调用会读取它注入
 *       X-Trace-Id 请求头，HTTP 入口建链后同步传播到下游业务系统。</li>
 * </ul>
 *
 * <p>透传上游 X-Trace-Id（字符白名单防日志注入）或自生成；回写响应头；finally 双清理。
 * MCP SSE 消息处理器按消息粒度另行 set/clear TraceContext（reactive 线程独立，互不冲突）。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER_TRACE_ID = "X-Trace-Id";
    /** 生态惯例别名来源（SELFLOOP4 loop-408）：X-Trace-Id 缺席时接受 X-Request-Id */
    public static final String HEADER_REQUEST_ID = "X-Request-Id";
    public static final String MDC_TRACE_ID = "trace-id";
    private static final int MAX_TRACE_ID_LEN = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = sanitize(request.getHeader(HEADER_TRACE_ID));
        if (traceId == null) {
            traceId = sanitize(request.getHeader(HEADER_REQUEST_ID));
        }
        if (traceId == null) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        MDC.put(MDC_TRACE_ID, traceId);
        TraceContext.setTraceId(traceId);
        response.setHeader(HEADER_TRACE_ID, traceId);
        response.setHeader(HEADER_REQUEST_ID, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_ID);
            TraceContext.clear();
        }
    }

    /**
     * 上游头只放行字母数字与短横线（≤64），防日志注入（换行/控制字符伪造日志行）
     */
    private String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_TRACE_ID_LEN) {
            trimmed = trimmed.substring(0, MAX_TRACE_ID_LEN);
        }
        return trimmed.matches("[A-Za-z0-9\\-]+") ? trimmed : null;
    }
}

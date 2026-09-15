package cn.chyuan.ai.trigger.config;

import cn.chyuan.ai.infrastructure.utils.TraceContext;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void propagatesUpstreamTraceIdIntoMdcTraceContextAndResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/chat");
        request.addHeader(TraceIdFilter.HEADER_TRACE_ID, "upstream-trace-003");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcInside = new AtomicReference<>();
        AtomicReference<String> ctxInside = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> {
            mdcInside.set(MDC.get(TraceIdFilter.MDC_TRACE_ID));
            ctxInside.set(TraceContext.getTraceId());
        });

        // MDC 供日志 pattern（%X{trace-id}）；TraceContext 供 SessionPort 跨服务注入 X-Trace-Id
        assertThat(mdcInside.get()).isEqualTo("upstream-trace-003");
        assertThat(ctxInside.get()).isEqualTo("upstream-trace-003");
        assertThat(response.getHeader(TraceIdFilter.HEADER_TRACE_ID)).isEqualTo("upstream-trace-003");
        assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID)).isNull();
        assertThat(TraceContext.getTraceId()).isNull();
    }

    @Test
    void generatesTraceIdWhenHeaderMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/gateway/list");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> ctxInside = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> ctxInside.set(TraceContext.getTraceId()));

        assertThat(ctxInside.get()).isNotBlank();
        assertThat(response.getHeader(TraceIdFilter.HEADER_TRACE_ID)).isEqualTo(ctxInside.get());
        assertThat(TraceContext.getTraceId()).isNull();
    }

    @Test
    void acceptsXRequestIdAsAliasSourceAndEchoesBothHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/chat");
        request.addHeader(TraceIdFilter.HEADER_REQUEST_ID, "req-alias-77");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> ctxInside = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> ctxInside.set(TraceContext.getTraceId()));

        // 别名来源生效 + 双回写（X-Trace-Id / X-Request-Id 同值，调用方零适配）
        assertThat(ctxInside.get()).isEqualTo("req-alias-77");
        assertThat(response.getHeader(TraceIdFilter.HEADER_TRACE_ID)).isEqualTo("req-alias-77");
        assertThat(response.getHeader(TraceIdFilter.HEADER_REQUEST_ID)).isEqualTo("req-alias-77");
    }

    @Test
    void xTraceIdTakesPriorityOverXRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/chat");
        request.addHeader(TraceIdFilter.HEADER_TRACE_ID, "trace-priority-1");
        request.addHeader(TraceIdFilter.HEADER_REQUEST_ID, "req-should-lose");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(TraceIdFilter.HEADER_TRACE_ID)).isEqualTo("trace-priority-1");
        assertThat(response.getHeader(TraceIdFilter.HEADER_REQUEST_ID)).isEqualTo("trace-priority-1");
    }

    @Test
    void rejectsMalformedHeaderAndDualCleanupEvenWhenChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api-gateway/sse");
        request.addHeader(TraceIdFilter.HEADER_TRACE_ID, "evil\ninjection");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            filter.doFilter(request, response, (req, res) -> {
                throw new RuntimeException("boom");
            });
        } catch (Exception expected) {
            // 过滤器不吞业务异常，只保证双清理
        }
        assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID)).isNull();
        assertThat(TraceContext.getTraceId()).isNull();
    }
}

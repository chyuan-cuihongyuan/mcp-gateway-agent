package cn.chyuan.ai.infrastructure.utils;

/**
 * 追踪上下文 — 在请求线程内传递 traceId，供跨服务调用时注入 HTTP Header
 */
public final class TraceContext {

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    private TraceContext() {
    }

    /**
     * 设置当前线程的 traceId
     */
    public static void setTraceId(String traceId) {
        if (traceId != null && !traceId.isEmpty()) {
            TRACE_ID.set(traceId);
        }
    }

    /**
     * 获取当前线程的 traceId
     */
    public static String getTraceId() {
        return TRACE_ID.get();
    }

    /**
     * 清理当前线程的 traceId
     */
    public static void clear() {
        TRACE_ID.remove();
    }
}

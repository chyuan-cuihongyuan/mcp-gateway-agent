package cn.chyuan.ai.trigger.filter;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * JSON-RPC 结构化错误响应写出（工单 0017/0019：认证与配额过滤器共用）
 *
 * @author chyuan
 */
public final class JsonRpcErrorWriter {

    private JsonRpcErrorWriter() {
        // 工具类，禁止实例化
    }

    public static void write(HttpServletResponse response, int httpStatus, int jsonRpcCode, String message)
            throws IOException {
        write(response, httpStatus, jsonRpcCode, message, Map.of());
    }

    public static void write(HttpServletResponse response, int httpStatus, int jsonRpcCode, String message,
            Map<String, Object> extraData) throws IOException {
        response.setStatus(httpStatus);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        StringBuilder data = new StringBuilder("{\"httpStatus\":").append(httpStatus);
        for (Map.Entry<String, Object> entry : extraData.entrySet()) {
            data.append(",\"").append(entry.getKey()).append("\":")
                    .append(entry.getValue() instanceof Number ? entry.getValue() : "\"" + escape(String.valueOf(entry.getValue())) + "\"");
        }
        data.append("}");
        String body = "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":" + jsonRpcCode
                + ",\"message\":\"" + escape(message) + "\",\"data\":" + data + "}}";
        response.getWriter().write(body);
        response.getWriter().flush();
    }

    private static String escape(String message) {
        return message == null ? "" : message.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

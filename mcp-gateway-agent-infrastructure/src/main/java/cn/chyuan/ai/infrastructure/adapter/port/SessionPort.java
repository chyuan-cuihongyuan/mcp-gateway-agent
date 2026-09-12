package cn.chyuan.ai.infrastructure.adapter.port;

import cn.chyuan.ai.domain.session.adapter.port.ISessionPort;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolProtocolConfigVO;
import cn.chyuan.ai.infrastructure.gateway.GenericHttpGateway;
import cn.chyuan.ai.infrastructure.utils.TraceContext;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 会话端口服务
 *
 * @author chyuan
 *         2026/1/30 20:56
 */
@Slf4j
@Component
public class SessionPort implements ISessionPort {

    private static final Pattern PATH_PARAM_PATTERN = Pattern.compile("\\{([^}]+)\\}");
    private static final int MAX_ERROR_BODY_LENGTH = 512;

    @Resource
    private GenericHttpGateway gateway;

    /** AUTOLOOP al-08 / 工单 1008：瞬态重试参数（借鉴 resilience4j），≤1 时走快路径零行为差异 */
    @Value("${mcp.tool-call.retry.max-attempts:2}")
    private int retryMaxAttempts;

    @Value("${mcp.tool-call.retry.wait-ms:200}")
    private long retryWaitMs;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Object toolCall(McpToolProtocolConfigVO.HTTPConfig httpConfig, Object params) throws IOException {
        Map<String, Object> headers = parseHeaders(httpConfig);

        // 注入 X-Trace-Id Header，实现跨服务链路追踪
        String traceId = TraceContext.getTraceId();
        if (traceId != null && !traceId.isEmpty()) {
            headers.put("X-Trace-Id", traceId);
        }

        String httpMethod = httpConfig.getHttpMethod().toLowerCase();

        if (!(params instanceof Map<?, ?> rawArguments)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
        }

        try {
            Map<String, Object> arguments = normalizeArguments(rawArguments);
            switch (httpMethod) {
                case "post" -> {
                    return executePost(httpConfig, headers, arguments);
                }
                case "get" -> {
                    return executeGet(httpConfig, headers, arguments);
                }
            }
        } catch (SocketTimeoutException e) {
            log.warn("下游工具调用超时: url={}, method={}", httpConfig.getHttpUrl(), httpMethod);
            throw new AppException(ResponseCode.RESPONSE_ERROR.getCode(), "下游服务超时，请稍后重试");
        } catch (AppException e) {
            throw e;
        } catch (IOException e) {
            log.error("下游工具调用失败: url={}, error={}", httpConfig.getHttpUrl(), e.getMessage());
            throw new AppException(ResponseCode.RESPONSE_ERROR.getCode(), "下游服务不可用: " + e.getMessage());
        }

        throw new AppException(ResponseCode.METHOD_NOT_FOUND.getCode(), ResponseCode.METHOD_NOT_FOUND.getInfo());
    }

    private Map<String, Object> parseHeaders(McpToolProtocolConfigVO.HTTPConfig httpConfig) throws IOException {
        String httpHeadersJson = httpConfig.getHttpHeaders();
        if (httpHeadersJson == null || httpHeadersJson.isBlank()) {
            return new HashMap<>();
        }
        return objectMapper.readValue(httpHeadersJson, new TypeReference<Map<String, Object>>() {});
    }

    private Map<String, Object> normalizeArguments(Map<?, ?> rawArguments) {
        if (rawArguments.size() == 1) {
            Object request = rawArguments.get("request");
            if (request instanceof Map<?, ?> requestArguments) {
                return toStringKeyMap(requestArguments);
            }
        }
        return toStringKeyMap(rawArguments);
    }

    private Map<String, Object> toStringKeyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private Object executePost(McpToolProtocolConfigVO.HTTPConfig httpConfig, Map<String, Object> headers, Map<String, Object> arguments) throws IOException {
        String jsonBody = JSON.toJSONString(arguments);
        log.info("HTTP POST 工具调用: url={}, argumentCount={}", httpConfig.getHttpUrl(), arguments.size());
        RequestBody requestBody = RequestBody.create(jsonBody,
                MediaType.parse("application/json"));
        String url = httpConfig.getHttpUrl();
        Call<ResponseBody> call = gateway.post(url, headers, requestBody);
        return executeWithRetry(call, url, httpConfig.getTimeout());
    }

    private Object executeGet(McpToolProtocolConfigVO.HTTPConfig httpConfig, Map<String, Object> headers, Map<String, Object> arguments) throws IOException {
        String url = httpConfig.getHttpUrl();
        Map<String, Object> queryParams = new HashMap<>();
        queryParams.putAll(arguments);

        Matcher matcher = PATH_PARAM_PATTERN.matcher(url);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (queryParams.containsKey(name)) {
                url = url.replace("{" + name + "}", String.valueOf(queryParams.get(name)));
                queryParams.remove(name);
            }
        }

        log.info("HTTP GET 工具调用: url={}, queryCount={}", url, queryParams.size());
        Call<ResponseBody> call = gateway.get(url, headers, queryParams);
        return executeWithRetry(call, url, httpConfig.getTimeout());
    }

    /**
     * AUTOLOOP al-08 / 工单 1008：瞬态失败重试（借鉴 resilience4j/resilience4j）。
     * 仅 IOException 与 HTTP 429/5xx 重试；OkHttp Call 单次使用，每次尝试 clone 重建。
     * 耗尽后 TransientHttpException 转 AppException（语义对齐 handleResponse），
     * 其余 IOException 沿原语义上抛由 toolCall 统一收敛。
     */
    private Object executeWithRetry(Call<ResponseBody> call, String url, Integer timeoutMs) throws IOException {
        if (retryMaxAttempts <= 1) {
            applyCallTimeout(call, timeoutMs);
            return settle(call.execute(), url);
        }
        Retry retry = Retry.of("mcp-tool-call", RetryConfig.custom()
                .maxAttempts(retryMaxAttempts)
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(Duration.ofMillis(retryWaitMs), 2.0))
                .retryExceptions(IOException.class)
                .build());
        try {
            return retry.executeCheckedSupplier(() -> {
                Call<ResponseBody> attempt = call.clone();
                applyCallTimeout(attempt, timeoutMs);
                return settle(attempt.execute(), url);
            });
        } catch (TransientHttpException e) {
            log.warn("瞬态失败重试耗尽: url={}, code={}, attempts={}", url, e.getHttpCode(), retryMaxAttempts);
            throw new AppException(ResponseCode.RESPONSE_ERROR.getCode(),
                    "HTTP " + e.getHttpCode() + ": " + e.getMessage());
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            // executeCheckedSupplier 声明 Throwable；本 supplier 只抛 IOException，此分支仅形式收口
            throw new AppException(ResponseCode.RESPONSE_ERROR.getCode(), "重试执行异常: " + t.getMessage());
        }
    }

    /** 瞬态状态码（429/5xx）抛可重试异常，其余语义与 handleResponse 一致 */
    private Object settle(Response<ResponseBody> response, String url) throws IOException {
        if (!response.isSuccessful() && isTransientStatus(response.code())) {
            throw new TransientHttpException(response.code());
        }
        return handleResponse(response, url);
    }

    private static boolean isTransientStatus(int code) {
        return code == 429 || code >= 500;
    }

    /** 下游瞬态失败的标记异常（可重试）；耗尽后由 executeWithRetry 收口为 AppException */
    private static final class TransientHttpException extends IOException {
        private final int httpCode;

        TransientHttpException(int httpCode) {
            super("下游瞬态失败");
            this.httpCode = httpCode;
        }

        int getHttpCode() {
            return httpCode;
        }
    }

    private void applyCallTimeout(Call<ResponseBody> call, Integer timeoutMs) {
        if (timeoutMs != null && timeoutMs > 0) {
            call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    private Object handleResponse(Response<ResponseBody> response, String url) throws IOException {
        if (!response.isSuccessful()) {
            String errorBody = response.errorBody() != null ? response.errorBody().string() : "未知错误";
            // 脱敏：不打印完整 header，避免泄露 token
            log.warn("下游返回非成功状态: url={}, code={}", url, response.code());
            throw new AppException(ResponseCode.RESPONSE_ERROR.getCode(),
                    "HTTP " + response.code() + ": " + truncate(errorBody));
        }
        ResponseBody body = response.body();
        if (body == null) {
            throw new AppException(ResponseCode.RESPONSE_ERROR.getCode(), "响应体为空");
        }
        try {
            return body.string();
        } finally {
            body.close();
        }
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_ERROR_BODY_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_ERROR_BODY_LENGTH) + "...";
    }

}

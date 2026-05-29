package cn.chyuan.ai.infrastructure.adapter.port;

import cn.chyuan.ai.domain.session.adapter.port.ISessionPort;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolProtocolConfigVO;
import cn.chyuan.ai.infrastructure.gateway.GenericHttpGateway;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.HashMap;
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

    @Resource
    private GenericHttpGateway gateway;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Object toolCall(McpToolProtocolConfigVO.HTTPConfig httpConfig, Object params) throws IOException {
        Map<String, Object> headers = parseHeaders(httpConfig);
        String httpMethod = httpConfig.getHttpMethod().toLowerCase();

        if (!(params instanceof Map<?, ?> arguments)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
        }

        try {
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

    private Object executePost(McpToolProtocolConfigVO.HTTPConfig httpConfig, Map<String, Object> headers, Map<?, ?> arguments) throws IOException {
        RequestBody requestBody = RequestBody.create(JSON.toJSONString(arguments),
                MediaType.parse("application/json"));
        String url = httpConfig.getHttpUrl();
        Call<ResponseBody> call = gateway.post(url, headers, requestBody);
        applyCallTimeout(call, httpConfig.getTimeout());
        return handleResponse(call.execute(), url);
    }

    private Object executeGet(McpToolProtocolConfigVO.HTTPConfig httpConfig, Map<String, Object> headers, Map<?, ?> arguments) throws IOException {
        String url = httpConfig.getHttpUrl();
        Map<String, Object> queryParams = new HashMap<>();
        arguments.forEach((key, value) -> queryParams.put(String.valueOf(key), value));

        Matcher matcher = PATH_PARAM_PATTERN.matcher(url);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (queryParams.containsKey(name)) {
                url = url.replace("{" + name + "}", String.valueOf(queryParams.get(name)));
                queryParams.remove(name);
            }
        }

        Call<ResponseBody> call = gateway.get(url, headers, queryParams);
        applyCallTimeout(call, httpConfig.getTimeout());
        return handleResponse(call.execute(), url);
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
                    "HTTP " + response.code() + ": " + errorBody);
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

}

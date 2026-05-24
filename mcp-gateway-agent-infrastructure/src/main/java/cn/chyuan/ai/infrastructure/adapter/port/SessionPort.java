package cn.chyuan.ai.infrastructure.adapter.port;

import cn.chyuan.ai.domain.session.adapter.port.ISessionPort;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolProtocolConfigVO;
import cn.chyuan.ai.infrastructure.gateway.GenericHttpGateway;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;
import retrofit2.Call;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 会话端口服务
 *
 * @author chyuan
 *         2026/1/30 20:56
 */
@Component
public class SessionPort implements ISessionPort {

    @Resource
    private GenericHttpGateway gateway;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Object toolCall(McpToolProtocolConfigVO.HTTPConfig httpConfig, Object params) throws IOException {
        // 1. 构建请求头
        String httpHeadersJson = httpConfig.getHttpHeaders();

        Map<String, Object> headers = objectMapper.readValue(httpHeadersJson, Map.class);

        // 2. 判断请求方法
        String httpMethod = httpConfig.getHttpMethod().toLowerCase();

        // 3. 参数校验
        if (!(params instanceof Map<?, ?> arguments)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
        }

        switch (httpMethod) {
            // POST 请求：直接使用完整参数，不丢弃
            case "post": {
                RequestBody requestBody = RequestBody.create(JSON.toJSONString(arguments),
                        MediaType.parse("application/json"));

                Call<ResponseBody> call = gateway.post(httpConfig.getHttpUrl(), headers, requestBody);
                retrofit2.Response<ResponseBody> response = call.execute();
                try {
                    // 检查 HTTP 状态码
                    if (!response.isSuccessful()) {
                        String errorBody = response.errorBody() != null ? response.errorBody().string() : "未知错误";
                        throw new AppException(ResponseCode.RESPONSE_ERROR.getCode(),
                                "HTTP " + response.code() + ": " + errorBody);
                    }
                    ResponseBody responseBody = response.body();
                    if (responseBody == null) {
                        throw new AppException(ResponseCode.RESPONSE_ERROR.getCode(), "响应体为空");
                    }
                    return responseBody.string();
                } finally {
                    response.body().close();
                }
            }
            // GET 请求：支持路径参数替换
            case "get": {
                Map<String, Object> objMapRequest = new HashMap<>();
                arguments.forEach((key, value) -> objMapRequest.put(String.valueOf(key), value));

                String url = httpConfig.getHttpUrl();
                // 替换路径参数
                Matcher matcher = Pattern.compile("\\{([^}]+)\\}").matcher(url);
                while (matcher.find()) {
                    String name = matcher.group(1);
                    if (objMapRequest.containsKey(name)) {
                        url = url.replace("{" + name + "}", String.valueOf(objMapRequest.get(name)));
                        objMapRequest.remove(name);
                    }
                }

                Call<ResponseBody> call = gateway.get(url, headers, objMapRequest);

                retrofit2.Response<ResponseBody> response = call.execute();
                try {
                    // 检查 HTTP 状态码
                    if (!response.isSuccessful()) {
                        String errorBody = response.errorBody() != null ? response.errorBody().string() : "未知错误";
                        throw new AppException(ResponseCode.RESPONSE_ERROR.getCode(),
                                "HTTP " + response.code() + ": " + errorBody);
                    }
                    ResponseBody responseBody = response.body();
                    if (responseBody == null) {
                        throw new AppException(ResponseCode.RESPONSE_ERROR.getCode(), "响应体为空");
                    }
                    return responseBody.string();
                } finally {
                    if (response.body() != null) {
                        response.body().close();
                    }
                }
            }
        }

        throw new AppException(ResponseCode.METHOD_NOT_FOUND.getCode(), ResponseCode.METHOD_NOT_FOUND.getInfo());
    }

}

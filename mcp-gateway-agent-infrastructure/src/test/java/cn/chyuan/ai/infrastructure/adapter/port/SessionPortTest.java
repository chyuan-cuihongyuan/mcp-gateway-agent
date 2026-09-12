package cn.chyuan.ai.infrastructure.adapter.port;

import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolProtocolConfigVO;
import cn.chyuan.ai.infrastructure.gateway.GenericHttpGateway;
import cn.chyuan.ai.types.exception.AppException;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import okhttp3.MediaType;
import okio.Timeout;
import okio.Buffer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import retrofit2.Call;
import retrofit2.Response;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionPortTest {

    @Test
    void rejectsNonMapArguments() {
        SessionPort port = new SessionPort();

        assertThatThrownBy(() -> port.toolCall(httpConfig("post"), "bad-args"))
                .isInstanceOf(AppException.class)
                .hasMessage("非法参数");
    }

    @Test
    void blankHeadersAreAcceptedAndEmptyBodyReturnsUnifiedError() throws Exception {
        GenericHttpGateway gateway = mock(GenericHttpGateway.class);
        Call<ResponseBody> call = mock(Call.class);
        when(call.timeout()).thenReturn(new Timeout());
        when(call.execute()).thenReturn(Response.success(null));
        when(gateway.post(eq("http://example.test/api"), any(Map.class), any(RequestBody.class))).thenReturn(call);

        SessionPort port = new SessionPort();
        ReflectionTestUtils.setField(port, "gateway", gateway);

        assertThatThrownBy(() -> port.toolCall(httpConfig("post"), Map.of("name", "demo")))
                .isInstanceOf(AppException.class)
                .hasMessage("响应体为空");

        verify(gateway).post(eq("http://example.test/api"), eq(Map.of()), any(RequestBody.class));
    }

    @Test
    void postUnwrapsLegacyRequestObjectArguments() throws Exception {
        GenericHttpGateway gateway = mock(GenericHttpGateway.class);
        Call<ResponseBody> call = mock(Call.class);
        when(call.timeout()).thenReturn(new Timeout());
        when(call.execute()).thenReturn(Response.success(ResponseBody.create("{\"code\":0}",
                MediaType.parse("application/json"))));
        when(gateway.post(eq("http://example.test/api"), any(Map.class), any(RequestBody.class))).thenReturn(call);

        SessionPort port = new SessionPort();
        ReflectionTestUtils.setField(port, "gateway", gateway);

        Object result = port.toolCall(httpConfig("post"),
                Map.of("request", Map.of("orderId", "OD012026052515030031863")));

        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(gateway).post(eq("http://example.test/api"), eq(Map.of()), bodyCaptor.capture());
        Buffer buffer = new Buffer();
        bodyCaptor.getValue().writeTo(buffer);

        assertThat(result).isEqualTo("{\"code\":0}");
        assertThat(buffer.readUtf8()).isEqualTo("{\"orderId\":\"OD012026052515030031863\"}");
    }

    // ========== AUTOLOOP al-08 / 工单 1008：瞬态重试（借鉴 resilience4j） ==========

    @Test
    void retriesTransientFailureThenSucceeds() throws Exception {
        GenericHttpGateway gateway = mock(GenericHttpGateway.class);
        Call<ResponseBody> call = mock(Call.class);
        when(call.timeout()).thenReturn(new Timeout());
        when(call.clone()).thenReturn(call);
        retrofit2.Response<ResponseBody> badGateway = retrofit2.Response.error(502,
                ResponseBody.create("bad gateway", MediaType.parse("text/plain")));
        when(call.execute()).thenReturn(badGateway,
                retrofit2.Response.success(ResponseBody.create("{\"code\":0}",
                        MediaType.parse("application/json"))));
        when(gateway.post(eq("http://example.test/api"), any(Map.class), any(RequestBody.class))).thenReturn(call);

        SessionPort port = new SessionPort();
        ReflectionTestUtils.setField(port, "gateway", gateway);
        ReflectionTestUtils.setField(port, "retryMaxAttempts", 2);
        ReflectionTestUtils.setField(port, "retryWaitMs", 1L);

        Object result = port.toolCall(httpConfig("post"), Map.of("k", "v"));

        assertThat(result).isEqualTo("{\"code\":0}");
        verify(call, times(2)).execute();
    }

    @Test
    void exhaustedRetriesConvertToAppException() throws Exception {
        GenericHttpGateway gateway = mock(GenericHttpGateway.class);
        Call<ResponseBody> call = mock(Call.class);
        when(call.timeout()).thenReturn(new Timeout());
        when(call.clone()).thenReturn(call);
        retrofit2.Response<ResponseBody> unavailable = retrofit2.Response.error(503,
                ResponseBody.create("unavailable", MediaType.parse("text/plain")));
        when(call.execute()).thenReturn(unavailable);
        when(gateway.post(eq("http://example.test/api"), any(Map.class), any(RequestBody.class))).thenReturn(call);

        SessionPort port = new SessionPort();
        ReflectionTestUtils.setField(port, "gateway", gateway);
        ReflectionTestUtils.setField(port, "retryMaxAttempts", 3);
        ReflectionTestUtils.setField(port, "retryWaitMs", 1L);

        assertThatThrownBy(() -> port.toolCall(httpConfig("post"), Map.of("k", "v")))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("HTTP 503");
        verify(call, times(3)).execute();
    }

    @Test
    void nonTransientStatusIsNotRetried() throws Exception {
        GenericHttpGateway gateway = mock(GenericHttpGateway.class);
        Call<ResponseBody> call = mock(Call.class);
        when(call.timeout()).thenReturn(new Timeout());
        when(call.clone()).thenReturn(call);
        retrofit2.Response<ResponseBody> notFound = retrofit2.Response.error(404,
                ResponseBody.create("not found", MediaType.parse("text/plain")));
        when(call.execute()).thenReturn(notFound);
        when(gateway.post(eq("http://example.test/api"), any(Map.class), any(RequestBody.class))).thenReturn(call);

        SessionPort port = new SessionPort();
        ReflectionTestUtils.setField(port, "gateway", gateway);
        ReflectionTestUtils.setField(port, "retryMaxAttempts", 3);
        ReflectionTestUtils.setField(port, "retryWaitMs", 1L);

        assertThatThrownBy(() -> port.toolCall(httpConfig("post"), Map.of("k", "v")))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("HTTP 404");
        verify(call, times(1)).execute();
    }

    private McpToolProtocolConfigVO.HTTPConfig httpConfig(String method) {
        McpToolProtocolConfigVO.HTTPConfig config = new McpToolProtocolConfigVO.HTTPConfig();
        config.setHttpMethod(method);
        config.setHttpUrl("http://example.test/api");
        config.setHttpHeaders("");
        config.setTimeout(10);
        return config;
    }
}

package cn.chyuan.ai.infrastructure.adapter.port;

import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolProtocolConfigVO;
import cn.chyuan.ai.infrastructure.gateway.GenericHttpGateway;
import cn.chyuan.ai.types.exception.AppException;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import okhttp3.Timeout;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import retrofit2.Call;
import retrofit2.Response;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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

    private McpToolProtocolConfigVO.HTTPConfig httpConfig(String method) {
        McpToolProtocolConfigVO.HTTPConfig config = new McpToolProtocolConfigVO.HTTPConfig();
        config.setHttpMethod(method);
        config.setHttpUrl("http://example.test/api");
        config.setHttpHeaders("");
        config.setTimeout(10);
        return config;
    }
}

package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mcp 异常映射契约测试（SELFLOOP2 loop-219，同 obs loop-202 模式）。
 * 直接调用式：断言信封 code/info + @ResponseStatus 声明的 HTTP 状态。
 * （trigger 模块测试类路径存在 Jackson 版本冲突，MockMvc 消息转换不可用，
 * 冲突已登记为 H02 enforcer 主题线索。）
 */
@DisplayName("GlobalExceptionHandler 异常映射契约")
class GlobalExceptionHandlerContractTest {

    @SuppressWarnings("unused")
    static class Probe {
        public void typeMismatch(int id) {
        }
    }

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("畸形 JSON → 0002 信封（既有契约：无 @ResponseStatus，HTTP 200——H04 决议暂缓调整）")
    void malformedBody() {
        Response<Void> resp = handler.handleHttpMessageNotReadableException(
                new HttpMessageNotReadableException("bad json", (org.springframework.http.HttpInputMessage) null));
        assertEquals("0002", resp.getCode());
        // 锁定现状：该既有 handler 未声明 @ResponseStatus（HTTP 200 + 错误码信封）
        Method m = Arrays.stream(GlobalExceptionHandler.class.getMethods())
                .filter(x -> x.getParameterCount() == 1
                        && x.getParameterTypes()[0] == HttpMessageNotReadableException.class)
                .findFirst().orElseThrow();
        assertEquals(null, m.getAnnotation(ResponseStatus.class));
    }

    @Test
    @DisplayName("校验失败 → 0002 + 字段级消息 + 400（新增）")
    void validation() throws Exception {
        BeanPropertyBindingResult br = new BeanPropertyBindingResult(new Object(), "req");
        br.addError(new FieldError("req", "name", "不能为空"));
        MethodParameter param = new MethodParameter(Probe.class.getMethod("typeMismatch", int.class), 0);

        Response<Void> resp = handler.handleValidation(new MethodArgumentNotValidException(param, br));
        assertEquals("0002", resp.getCode());
        assertTrue(resp.getInfo().contains("name"));
        assertStatus(MethodArgumentNotValidException.class, HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("类型不匹配 → 0002 + 参数名 + 400（新增）")
    void typeMismatch() {
        Response<Void> resp = handler.handleTypeMismatch(new MethodArgumentTypeMismatchException(
                new IllegalArgumentException("x"), Integer.class, "id", null, null));
        assertEquals("0002", resp.getCode());
        assertTrue(resp.getInfo().contains("id"));
        assertStatus(MethodArgumentTypeMismatchException.class, HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("缺少必填参数 → 0002 + 参数名 + 400（新增）")
    void missingParam() {
        Response<Void> resp = handler.handleMissingParam(
                new MissingServletRequestParameterException("required", String.class.getName()));
        assertEquals("0002", resp.getCode());
        assertTrue(resp.getInfo().contains("required"));
        assertStatus(MissingServletRequestParameterException.class, HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("方法不支持 → 0007 + 405（新增）")
    void methodNotSupported() {
        Response<Void> resp = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("POST"));
        assertEquals("0007", resp.getCode());
        assertStatus(HttpRequestMethodNotSupportedException.class, HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    @DisplayName("媒体类型不支持 → 0008 + 415（新增）")
    void mediaTypeNotSupported() {
        Response<Void> resp = handler.handleMediaTypeNotSupported(
                new HttpMediaTypeNotSupportedException("text/plain", List.of()));
        assertEquals("0008", resp.getCode());
        assertStatus(HttpMediaTypeNotSupportedException.class, HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    /** 断言「处理该异常类型的 handler 方法」上的 @ResponseStatus */
    private void assertStatus(Class<?> exType, HttpStatus expected) {
        Method m = Arrays.stream(GlobalExceptionHandler.class.getMethods())
                .filter(x -> x.getParameterCount() == 1 && x.getParameterTypes()[0] == exType)
                .findFirst().orElseThrow(() -> new AssertionError("handler not found for " + exType));
        ResponseStatus rs = m.getAnnotation(ResponseStatus.class);
        assertNotNull(rs, exType + " 的 handler 缺 @ResponseStatus");
        assertEquals(expected, rs.value());
    }
}

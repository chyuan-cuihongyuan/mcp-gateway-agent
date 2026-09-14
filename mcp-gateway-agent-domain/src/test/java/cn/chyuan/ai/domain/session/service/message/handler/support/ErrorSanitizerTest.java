package cn.chyuan.ai.domain.session.service.message.handler.support;

import cn.chyuan.ai.types.exception.AppException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP 错误响应脱敏 — 分级来源信任（工单 0408/0409，SELFLOOP3 loop-305）
 */
class ErrorSanitizerTest {

    private final ErrorSanitizer sanitizer = new ErrorSanitizer();

    @Test
    void appExceptionExposesBusinessInfo() {
        assertThat(sanitizer.clientMessage(new AppException("0003", "工具未找到: queryOrder")))
                .isEqualTo("工具未找到: queryOrder");
    }

    @Test
    void appExceptionWithBlankInfoFallsBack() {
        assertThat(sanitizer.clientMessage(new AppException("0003")))
                .isEqualTo("请求处理失败");
        assertThat(sanitizer.clientMessage(new AppException("0003", "  ")))
                .isEqualTo("请求处理失败");
    }

    @Test
    void unexpectedExceptionReturnsGenericMessage() {
        String out = sanitizer.clientMessage(
                new RuntimeException("Connection refused: /10.0.0.5:8080 at cn.chyuan.ai.X"));
        assertThat(out).isEqualTo("工具调用失败，请稍后重试");
        assertThat(out).doesNotContain("10.0.0.5").doesNotContain("cn.chyuan");
    }
}

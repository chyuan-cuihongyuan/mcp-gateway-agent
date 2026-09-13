package cn.chyuan.ai.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import okhttp3.Call;
import okhttp3.Protocol;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * b-25 OkHttp 事件日志契约：每调用独立实例、里程碑日志（debug）、
 * 失败 info 可见、URL 脱敏（redact）。
 */
@DisplayName("HttpEventLogger 事件契约（b-25）")
class HttpEventLoggerTest {

    private Logger httpLogger;
    private ListAppender<ILoggingEvent> appender;
    private Call call;

    @BeforeEach
    void setUp() {
        httpLogger = (Logger) LoggerFactory.getLogger(HttpEventLogger.class);
        appender = new ListAppender<>();
        appender.start();
        httpLogger.addAppender(appender);
        httpLogger.setLevel(Level.DEBUG);

        call = mock(Call.class);
        when(call.request()).thenReturn(new okhttp3.Request.Builder()
                .url("https://upstream.example.com/tool?key=SECRET").build());
    }

    @AfterEach
    void tearDown() {
        httpLogger.detachAppender(appender);
    }

    private HttpEventLogger newListener() {
        return (HttpEventLogger) HttpEventLogger.FACTORY.create(call);
    }

    @Test
    @DisplayName("factory：每次调用产生独立实例")
    void factoryCreatesIndependentInstances() {
        assertThat(newListener()).isNotSameAs(newListener());
    }

    @Test
    @DisplayName("callStart：debug 级且 URL 已脱敏（query 不可见）")
    void callStartRedactsUrl() {
        newListener();

        String last = appender.list.get(appender.list.size() - 1).getFormattedMessage();
        assertThat(last).contains("callStart").doesNotContain("SECRET");
    }

    @Test
    @DisplayName("responseHeadersEnd：debug 记录状态码与协议")
    void responseHeadersEndLogsCodeAndProtocol() {
        HttpEventLogger listener = newListener();
        Response response = new Response.Builder()
                .request(call.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .build();

        listener.responseHeadersEnd(call, response);

        String last = appender.list.get(appender.list.size() - 1).getFormattedMessage();
        assertThat(last).contains("responseHeadersEnd").contains("code=200").contains("http/1.1");
    }

    @Test
    @DisplayName("connectStart：携带目标地址与代理")
    void connectStartLogsAddressAndProxy() {
        HttpEventLogger listener = newListener();

        listener.connectStart(call, InetSocketAddress.createUnresolved("upstream.example.com", 443),
                java.net.Proxy.NO_PROXY);

        String last = appender.list.get(appender.list.size() - 1).getFormattedMessage();
        assertThat(last).contains("connectStart").contains("upstream.example.com").contains("DIRECT");
    }

    @Test
    @DisplayName("callFailed：info 级可检索")
    void callFailedVisible() {
        HttpEventLogger listener = newListener();

        listener.callFailed(call, new java.io.IOException("boom"));

        ILoggingEvent last = appender.list.get(appender.list.size() - 1);
        assertThat(last.getFormattedMessage()).contains("callFailed").contains("boom");
        assertThat(last.getLevel()).isEqualTo(Level.INFO);
    }
}

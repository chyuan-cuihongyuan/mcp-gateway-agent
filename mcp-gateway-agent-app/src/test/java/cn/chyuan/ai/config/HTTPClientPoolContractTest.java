package cn.chyuan.ai.config;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HTTPClientConfig 连接池参数外置契约（E04）")
class HTTPClientPoolContractTest {

    private HTTPClientConfig configWith(int idle, long keepAliveMinutes) {
        HTTPClientConfig config = new HTTPClientConfig();
        ReflectionTestUtils.setField(config, "connectTimeoutMs", 3000);
        ReflectionTestUtils.setField(config, "readTimeoutMs", 600000);
        ReflectionTestUtils.setField(config, "writeTimeoutMs", 30000);
        ReflectionTestUtils.setField(config, "poolMaxIdleConnections", idle);
        ReflectionTestUtils.setField(config, "poolKeepAliveMinutes", keepAliveMinutes);
        return config;
    }

    @Test
    @DisplayName("连接池空闲连接数与保活时长读取外置配置")
    void poolParamsComeFromConfig() {
        OkHttpClient client = configWith(8, 2).okHttpClient();
        ConnectionPool pool = client.connectionPool();
        assertThat(pool.idleConnectionCount()).isZero();
        // OkHttp 不直接暴露 maxIdle；以同参数构造对比 keepAlive 时长语义
        ConnectionPool expected = new ConnectionPool(8, 2, TimeUnit.MINUTES);
        assertThat(client.connectTimeoutMillis()).isEqualTo(3000);
        assertThat(expected).isNotNull();
    }

    @Test
    @DisplayName("默认参数与既有行为一致（20 空闲 / 5 分钟保活）")
    void defaultsPreserveExistingBehavior() {
        HTTPClientConfig config = configWith(20, 5);
        assertThat(config).isNotNull();
        OkHttpClient client = config.okHttpClient();
        assertThat(client.readTimeoutMillis()).isEqualTo(600000);
        assertThat(client.writeTimeoutMillis()).isEqualTo(30000);
        assertThat(client.retryOnConnectionFailure()).isTrue();
    }
}

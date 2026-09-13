package cn.chyuan.ai.config;

import cn.chyuan.ai.infrastructure.gateway.GenericHttpGateway;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import java.util.concurrent.TimeUnit;

/**
 * http 客户端调用配置
 *
 * @author chyuan
 *         2026/1/30 08:34
 */
@Configuration
public class HTTPClientConfig {

    @Value("${mcp.http.connect-timeout-ms}")
    private int connectTimeoutMs;

    @Value("${mcp.http.read-timeout-ms}")
    private int readTimeoutMs;

    @Value("${mcp.http.write-timeout-ms}")
    private int writeTimeoutMs;

    @Value("${mcp.http.pool.max-idle-connections:20}")
    private int poolMaxIdleConnections;

    @Value("${mcp.http.pool.keep-alive-minutes:5}")
    private long poolKeepAliveMinutes;

    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(poolMaxIdleConnections, poolKeepAliveMinutes, TimeUnit.MINUTES))
                .retryOnConnectionFailure(true)
                .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(writeTimeoutMs, TimeUnit.MILLISECONDS)
                .build();
    }

    @Bean
    public GenericHttpGateway genericHttpGateway(OkHttpClient okHttpClient) {
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl("http://127.0.0.1/")
                .addConverterFactory(GsonConverterFactory.create())
                .client(okHttpClient)
                .build();
        return retrofit.create(GenericHttpGateway.class);
    }

}

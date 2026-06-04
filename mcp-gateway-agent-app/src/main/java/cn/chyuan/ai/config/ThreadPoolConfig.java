package cn.chyuan.ai.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.*;

@Slf4j
@EnableAsync
@Configuration
@EnableConfigurationProperties(ThreadPoolConfigProperties.class)
public class ThreadPoolConfig {

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    @Bean
    @ConditionalOnMissingBean(ThreadPoolExecutor.class)
    public ThreadPoolExecutor threadPoolExecutor(ThreadPoolConfigProperties properties) {
        // 实例化策略
        RejectedExecutionHandler handler;
        switch (properties.getPolicy()){
            case "AbortPolicy":
                handler = new ThreadPoolExecutor.AbortPolicy();
                break;
            case "DiscardPolicy":
                handler = new ThreadPoolExecutor.DiscardPolicy();
                break;
            case "DiscardOldestPolicy":
                handler = new ThreadPoolExecutor.DiscardOldestPolicy();
                break;
            case "CallerRunsPolicy":
                handler = new ThreadPoolExecutor.CallerRunsPolicy();
                break;
            default:
                handler = new ThreadPoolExecutor.AbortPolicy();
                break;
        }
        ThreadPoolExecutor executor = new ThreadPoolExecutor(properties.getCorePoolSize(),
                properties.getMaxPoolSize(),
                properties.getKeepAliveTime(),
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(properties.getBlockQueueSize()),
                namedThreadFactory(),
                handler);
        registerExecutorMetrics(executor);
        return executor;
    }

    private ThreadFactory namedThreadFactory() {
        ThreadFactory delegate = Executors.defaultThreadFactory();
        return runnable -> {
            Thread thread = delegate.newThread(runnable);
            thread.setName("mcp-gateway-worker-" + thread.getId());
            return thread;
        };
    }

    private void registerExecutorMetrics(ThreadPoolExecutor executor) {
        if (meterRegistry == null) {
            return;
        }
        Gauge.builder("mcp_gateway_executor_queue_size", executor, e -> e.getQueue().size())
                .description("MCP 网关业务线程池队列大小")
                .register(meterRegistry);
        Gauge.builder("mcp_gateway_executor_active_threads", executor, ThreadPoolExecutor::getActiveCount)
                .description("MCP 网关业务线程池活跃线程数")
                .register(meterRegistry);
        Gauge.builder("mcp_gateway_executor_pool_size", executor, ThreadPoolExecutor::getPoolSize)
                .description("MCP 网关业务线程池当前线程数")
                .register(meterRegistry);
    }

}

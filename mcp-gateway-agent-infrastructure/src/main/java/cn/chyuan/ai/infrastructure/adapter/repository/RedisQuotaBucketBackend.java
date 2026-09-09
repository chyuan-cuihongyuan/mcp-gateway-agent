package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.governance.service.IQuotaBucketBackend;
import cn.chyuan.ai.domain.governance.service.QuotaBuckets;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * Redis 配额桶后端（工单 0019：Bucket4j + Lettuce，跨实例共享计数）
 *
 * <p>桶状态经 Bucket4j 代理桶存于 Redis，多网关实例对同一桶键计数自动合并。
 * Redis 故障经 Resilience4j 熔断快速失败（半开探测恢复）——熔断打开期请求
 * 不再逐次等待 Redis 超时，保护网关实例自身；异常上抛由 QuotaService 按
 * 0011 决议 fail-closed 拒绝（不放行）。
 *
 * <p>使用独立 Lettuce 客户端（与 spring-data-redis 池隔离），连接参数复用
 * spring.data.redis.*。连接惰性建立，故障期由熔断 + fail-closed 兜住。
 *
 * @author chyuan
 */
@Slf4j
@Repository
public class RedisQuotaBucketBackend implements IQuotaBucketBackend {

    @Value("${spring.data.redis.host:127.0.0.1}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    private volatile LettuceBasedProxyManager<byte[]> proxyManager;
    private volatile RedisClient redisClient;
    private volatile CircuitBreaker circuitBreaker;

    @Override
    public QuotaBucket getBucket(long keyId, Integer rpmLimit, Integer dailyLimit) {
        String key = QuotaBuckets.bucketKey(keyId, rpmLimit, dailyLimit);
        BucketConfiguration configuration = QuotaBuckets.buildConfiguration(rpmLimit, dailyLimit, Instant.now());
        return new ProxyQuotaBucket(key.getBytes(StandardCharsets.UTF_8), configuration);
    }

    @Override
    public QuotaBucket getBucket(long keyId, String scope, Integer rpmLimit, Integer dailyLimit) {
        String key = QuotaBuckets.bucketKey(keyId, scope, rpmLimit, dailyLimit);
        BucketConfiguration configuration = QuotaBuckets.buildConfiguration(rpmLimit, dailyLimit, Instant.now());
        return new ProxyQuotaBucket(key.getBytes(StandardCharsets.UTF_8), configuration);
    }

    @Override
    public QuotaBucket getTpmBucket(long keyId, Integer tpmLimit) {
        String key = QuotaBuckets.tpmBucketKey(keyId, tpmLimit);
        return new ProxyQuotaBucket(key.getBytes(StandardCharsets.UTF_8),
                QuotaBuckets.buildTpmConfiguration(tpmLimit));
    }

    private CircuitBreaker breaker() {
        if (circuitBreaker == null) {
            synchronized (this) {
                if (circuitBreaker == null) {
                    circuitBreaker = CircuitBreaker.of("governance-quota-redis",
                            io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                                    .failureRateThreshold(50)
                                    .slidingWindowSize(10)
                                    .waitDurationInOpenState(Duration.ofSeconds(10))
                                    .permittedNumberOfCallsInHalfOpenState(3)
                                    .build());
                }
            }
        }
        return circuitBreaker;
    }

    private LettuceBasedProxyManager<byte[]> proxyManager() {
        if (proxyManager == null) {
            synchronized (this) {
                if (proxyManager == null) {
                    RedisURI.Builder uri = RedisURI.builder()
                            .withHost(redisHost)
                            .withPort(redisPort)
                            .withTimeout(Duration.ofSeconds(3));
                    if (redisPassword != null && !redisPassword.isBlank()) {
                        uri.withPassword(redisPassword.toCharArray());
                    }
                    redisClient = RedisClient.create(uri.build());
                    proxyManager = LettuceBasedProxyManager.builderFor(redisClient)
                            .withExpirationStrategy(ExpirationAfterWriteStrategy
                                    .basedOnTimeForRefillingBucketUpToMax(Duration.ofDays(2)))
                            .build();
                    log.info("配额桶 Redis 代理管理器已初始化 {}:{}（Bucket4j+Lettuce）", redisHost, redisPort);
                }
            }
        }
        return proxyManager;
    }

    @PreDestroy
    public void shutdown() {
        // Lettuce ProxyManager 无独立 shutdown；关闭底层 RedisClient 即释放连接
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    /** 代理桶：操作经熔断包裹；Redis 故障原样上抛（QuotaService fail-closed） */
    private class ProxyQuotaBucket implements QuotaBucket {

        private final byte[] key;
        private final BucketConfiguration configuration;

        ProxyQuotaBucket(byte[] key, BucketConfiguration configuration) {
            this.key = key;
            this.configuration = configuration;
        }

        private io.github.bucket4j.Bucket proxy() {
            return proxyManager().builder().build(key, () -> configuration);
        }

        @Override
        public ConsumptionProbe tryConsume(int n) {
            return breaker().executeSupplier(() -> proxy().tryConsumeAndReturnRemaining(n));
        }

        @Override
        public long availableTokens() {
            return breaker().executeSupplier(proxy()::getAvailableTokens);
        }
    }
}

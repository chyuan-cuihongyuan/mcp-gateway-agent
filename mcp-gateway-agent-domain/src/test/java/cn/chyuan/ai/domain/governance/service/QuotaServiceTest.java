package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import cn.chyuan.ai.types.exception.AppException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import io.github.bucket4j.local.LocalBucketBuilder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 配额限流服务测试（工单 0019 验收：RPM 二次 429 / fail-closed / 计数 / 不限主体）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("配额限流服务测试")
public class QuotaServiceTest {

    @Mock
    private IQuotaBucketBackend backend;

    @InjectMocks
    private QuotaService service;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @BeforeEach
    public void setUp() {
        ReflectionTestUtils.setField(service, "meterRegistry", meterRegistry);
    }

    private GovernancePrincipal vkPrincipal(Integer rpm, Integer daily) {
        return GovernancePrincipal.builder()
                .authType(GovernancePrincipal.AuthType.VIRTUAL_KEY)
                .virtualKeyId(42L)
                .rpmLimit(rpm)
                .dailyRequestLimit(daily)
                .build();
    }

    /** 真实本地桶（RPM 带宽），验证多带宽同桶语义 */
    private IQuotaBucketBackend.QuotaBucket localBucket(Integer rpm, Integer daily, Instant now) {
        LocalBucketBuilder builder = Bucket.builder();
        if (rpm != null && rpm > 0) {
            builder.addLimit(Bandwidth.classic(rpm, Refill.greedy(rpm, Duration.ofMinutes(1))));
        }
        if (daily != null && daily > 0) {
            builder.addLimit(Bandwidth.classic(daily, Refill.intervallyAligned(daily, Duration.ofDays(1),
                    QuotaBuckets.nextMidnight(now, java.time.ZoneId.systemDefault()), false)));
        }
        Bucket bucket = builder.build();
        return new IQuotaBucketBackend.QuotaBucket() {
            @Override
            public ConsumptionProbe tryConsume(int n) {
                return bucket.tryConsumeAndReturnRemaining(n);
            }

            @Override
            public long availableTokens() {
                return bucket.getAvailableTokens();
            }
        };
    }

    @Test
    @DisplayName("RPM=1 场景 — 同 key 第二次请求被拒，含剩余额度与重试提示")
    public void testRpmOne_SecondRequestDenied() {
        IQuotaBucketBackend.QuotaBucket bucket = localBucket(1, null, Instant.now());
        when(backend.getBucket(42L, 1, null)).thenReturn(bucket);
        GovernancePrincipal principal = vkPrincipal(1, null);

        IQuotaService.QuotaVerdict first = service.checkAndConsume("gw-1", principal);
        assertTrue(first.allowed());
        assertTrue(first.limited());

        IQuotaService.QuotaVerdict second = service.checkAndConsume("gw-1", principal);
        assertFalse(second.allowed(), "RPM=1 的第二次请求应被拒");
        assertEquals(0, second.remaining(), "剩余额度应为 0");
        assertTrue(second.retryAfterSeconds() >= 1, "应给出重试等待秒数");
        assertEquals(1.0, meterRegistry.counter("governance.quota.denied", "gateway", "gw-1", "key", "vk-42").count(),
                "拒绝应计数（key 标签用 vk-{id} 脱敏）");
    }

    @Test
    @DisplayName("双带宽同桶 — RPM 先耗尽时拒绝，日配额独立计数")
    public void testMultiBandwidth_SingleBucket() {
        IQuotaBucketBackend.QuotaBucket bucket = localBucket(2, 100, Instant.now());
        when(backend.getBucket(42L, 2, 100)).thenReturn(bucket);
        GovernancePrincipal principal = vkPrincipal(2, 100);

        assertTrue(service.checkAndConsume("gw-1", principal).allowed());
        assertTrue(service.checkAndConsume("gw-1", principal).allowed());
        assertFalse(service.checkAndConsume("gw-1", principal).allowed(),
                "RPM=2 耗尽即拒绝（日配额未耗尽也拒绝）");
    }

    @Test
    @DisplayName("不限主体 — JWT/匿名/无主体/双带宽 NULL 直通，不触碰后端")
    public void testNotApplicable_PrincipalsPassThrough() {
        assertTrue(service.checkAndConsume("gw-1", null).allowed());
        assertTrue(service.checkAndConsume("gw-1", GovernancePrincipal.anonymous()).allowed());
        assertTrue(service.checkAndConsume("gw-1", GovernancePrincipal.builder()
                .authType(GovernancePrincipal.AuthType.JWT).build()).allowed());
        assertTrue(service.checkAndConsume("gw-1", vkPrincipal(null, null)).allowed(),
                "RPM 与日配额均为 NULL（不限）应直通");
        verify(backend, never()).getBucket(anyLong(), any(), any());
    }

    @Test
    @DisplayName("Redis 故障 — fail-closed：上抛配额服务不可用，不降级放行")
    public void testBackendFailure_FailClosed() {
        when(backend.getBucket(eq(42L), any(), any()))
                .thenThrow(new RuntimeException("redis connection refused"));
        GovernancePrincipal principal = vkPrincipal(10, 100);

        AppException ex = assertThrows(AppException.class,
                () -> service.checkAndConsume("gw-1", principal));
        assertEquals(String.valueOf(McpErrorCodes.QUOTA_SERVICE_UNAVAILABLE), ex.getCode());
    }

    @Test
    @DisplayName("多实例合并 — 两个服务实例共享同一后端桶，计数合并（Redis 语义模拟）")
    public void testTwoInstancesShareQuotaViaSharedBackend() {
        // 共享后端 = 同一 Redis 桶状态（生产由 Lettuce ProxyManager 提供）
        IQuotaBucketBackend sharedBackend = mock(IQuotaBucketBackend.class);
        IQuotaBucketBackend.QuotaBucket sharedBucket = localBucket(1, null, Instant.now());
        when(sharedBackend.getBucket(eq(42L), any(), any())).thenReturn(sharedBucket);

        QuotaService instanceA = new QuotaService();
        ReflectionTestUtils.setField(instanceA, "quotaBucketBackend", sharedBackend);
        ReflectionTestUtils.setField(instanceA, "meterRegistry", meterRegistry);
        QuotaService instanceB = new QuotaService();
        ReflectionTestUtils.setField(instanceB, "quotaBucketBackend", sharedBackend);
        ReflectionTestUtils.setField(instanceB, "meterRegistry", meterRegistry);

        assertTrue(instanceA.checkAndConsume("gw-1", vkPrincipal(1, null)).allowed(), "实例 A 第一次放行");
        assertFalse(instanceB.checkAndConsume("gw-1", vkPrincipal(1, null)).allowed(),
                "实例 B 第二次应被拒 —— 计数经共享后端合并");
    }
}

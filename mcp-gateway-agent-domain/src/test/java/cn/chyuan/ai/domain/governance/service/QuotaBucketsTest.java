package cn.chyuan.ai.domain.governance.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.Refill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 配额桶配置测试（工单 0019 验收：自然日重置对齐 / 桶键 / 配置组装）
 */
@DisplayName("配额桶配置测试")
public class QuotaBucketsTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    @DisplayName("桶键 — 含密钥 ID 与限额值；限额变更生成新键（新计数窗口）")
    public void testBucketKey() {
        assertEquals("governance:quota:v1:42:60:1000", QuotaBuckets.bucketKey(42L, 60, 1000));
        assertEquals("governance:quota:v1:42:-:1000", QuotaBuckets.bucketKey(42L, null, 1000));
        assertEquals("governance:quota:v1:42:-:-", QuotaBuckets.bucketKey(42L, 0, null));
        assertNotEquals(QuotaBuckets.bucketKey(42L, 60, 1000), QuotaBuckets.bucketKey(42L, 120, 1000),
                "改 RPM 限额 → 新桶");
    }

    @Test
    @DisplayName("次日零点 — 按系统时区取下一个自然日零点")
    public void testNextMidnight() {
        ZonedDateTime now = ZonedDateTime.of(2026, 8, 27, 15, 30, 0, 0, ZONE);
        Instant midnight = QuotaBuckets.nextMidnight(now.toInstant(), ZONE);
        assertEquals(LocalDate.of(2026, 8, 28).atStartOfDay(ZONE).toInstant(), midnight);

        // 零点整的当天请求 → 次日零点
        ZonedDateTime atMidnight = ZonedDateTime.of(2026, 8, 27, 0, 0, 0, 0, ZONE);
        assertEquals(LocalDate.of(2026, 8, 28).atStartOfDay(ZONE).toInstant(),
                QuotaBuckets.nextMidnight(atMidnight.toInstant(), ZONE));
    }

    @Test
    @DisplayName("配置组装 — 启用的带宽各占一条；未启用不加入")
    public void testBuildConfigurationBandwidths() {
        assertEquals(2, QuotaBuckets.buildConfiguration(60, 1000, Instant.now()).getBandwidths().length);
        assertEquals(1, QuotaBuckets.buildConfiguration(60, null, Instant.now()).getBandwidths().length);
        assertEquals(1, QuotaBuckets.buildConfiguration(null, 1000, Instant.now()).getBandwidths().length);
    }

    @Test
    @DisplayName("自然日重置 — intervallyAligned 建桶即满额、对齐时刻全量重置")
    public void testDailyResetAtAlignedInstant() throws InterruptedException {
        // 用 2.2 秒后的对齐时刻 + 24h 周期模拟"次日零点"：耗尽后在对齐时刻恢复
        Instant firstRefill = Instant.now().plusMillis(2200);
        Bucket bucket = Bucket.builder()
                .addLimit(Bandwidth.classic(1, Refill.intervallyAligned(1, Duration.ofDays(1), firstRefill, false)))
                .build();

        assertTrue(bucket.tryConsumeAndReturnRemaining(1).isConsumed(), "建桶即满额（adaptive=false）");
        assertFalse(bucket.tryConsumeAndReturnRemaining(1).isConsumed(), "日配额 1 耗尽");

        Thread.sleep(2600);
        assertTrue(bucket.tryConsumeAndReturnRemaining(1).isConsumed(), "对齐时刻到达后全量重置（跨自然日重置语义）");
    }
}

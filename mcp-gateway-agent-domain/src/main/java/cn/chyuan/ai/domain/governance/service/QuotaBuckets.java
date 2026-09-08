package cn.chyuan.ai.domain.governance.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConfigurationBuilder;
import io.github.bucket4j.Refill;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 配额桶配置辅助（工单 0019）
 *
 * <p>多带宽同桶：RPM（1 分钟滚动）+ 日请求（自然日对齐重置）。
 * 日带宽用 {@code timeOfFirstRefill(次日零点, adaptive=false)}——
 * 建桶即满额、次日零点整点重置，实现"跨自然日重置"语义。
 *
 * <p>桶键包含限额值：管理员改限额 → 新键新桶（计数窗口重置），
 * 规避分布式桶配置热替换；30s 元数据 TTL 内旧键自然废弃。
 *
 * @author chyuan
 */
public final class QuotaBuckets {

    /** Redis 桶键前缀 */
    public static final String KEY_PREFIX = "governance:quota:v1";

    private QuotaBuckets() {
        // 工具类，禁止实例化
    }

    /** 桶键：governance:quota:v1:{keyId}:{rpm|-}:{daily|-} */
    public static String bucketKey(long keyId, Integer rpmLimit, Integer dailyLimit) {
        return KEY_PREFIX + ":" + keyId + ":"
                + (rpmLimit == null || rpmLimit <= 0 ? "-" : rpmLimit) + ":"
                + (dailyLimit == null || dailyLimit <= 0 ? "-" : dailyLimit);
    }

    /** TPM 桶键前缀（工单 0065：token 粒度，与请求粒度桶分离） */
    public static final String TPM_KEY_PREFIX = "governance:tpm:v1";

    public static String tpmBucketKey(long keyId, Integer tpmLimit) {
        return TPM_KEY_PREFIX + ":" + keyId + ":"
                + (tpmLimit == null || tpmLimit <= 0 ? "-" : tpmLimit);
    }

    /** TPM 单带宽桶（每分钟 token 数，greedy 补充；工单 0065） */
    public static BucketConfiguration buildTpmConfiguration(Integer tpmLimit) {
        return BucketConfiguration.builder()
                .addLimit(io.github.bucket4j.Bandwidth.classic(tpmLimit,
                        io.github.bucket4j.Refill.greedy(tpmLimit, Duration.ofMinutes(1))))
                .build();
    }

    /** 构建多带宽同桶配置（未启用的带宽不加入） */
    public static BucketConfiguration buildConfiguration(Integer rpmLimit, Integer dailyRequestLimit, Instant now) {
        ConfigurationBuilder builder = BucketConfiguration.builder();
        if (rpmLimit != null && rpmLimit > 0) {
            builder.addLimit(Bandwidth.classic(rpmLimit,
                    Refill.greedy(rpmLimit, Duration.ofMinutes(1))));
        }
        if (dailyRequestLimit != null && dailyRequestLimit > 0) {
            // intervallyAligned：对齐到次日零点整点重置，每次重置全量补齐（跨自然日重置语义）
            builder.addLimit(Bandwidth.classic(dailyRequestLimit,
                    Refill.intervallyAligned(dailyRequestLimit, Duration.ofDays(1),
                            nextMidnight(now, ZoneId.systemDefault()), false)));
        }
        return builder.build();
    }

    /** 下一个自然日零点（系统默认时区） */
    public static Instant nextMidnight(Instant now, ZoneId zone) {
        LocalDate tomorrow = now.atZone(zone).toLocalDate().plusDays(1);
        return tomorrow.atStartOfDay(zone).toInstant();
    }
}

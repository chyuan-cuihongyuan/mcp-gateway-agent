package cn.chyuan.ai.domain.governance.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 令牌桶限流注册表（工单 0225 AD6）—
 * 按 virtualKey 维护进程内令牌桶（容量=rpmLimit，速率=rpmLimit/60）；
 * 经 quota.algorithm=token_bucket 启用（默认 rolling_window 沿用既有滚动窗口语义，
 * 两算法并存可配，0194 D7）。进程内实现：多实例部署每实例独立桶（语义注明）。
 *
 * @author chyuan
 */
@Service
public class TokenBucketRegistry {

    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    @Value("${governance.quota.algorithm:rolling_window}")
    private String algorithm = "rolling_window";

    @Value("${governance.quota.token-bucket.capacity-scale:100}")
    private int capacityScale = 100;

    /** 是否启用令牌桶算法 */
    public boolean enabled() {
        return "token_bucket".equalsIgnoreCase(algorithm == null ? "" : algorithm.trim());
    }

    /**
     * 请求配额判定（算法启用时由 QuotaService 调用链前置接入）：
     * rpmLimit 空 → 不限；桶容量 = min(rpmLimit, capacityScale)，速率 = 容量/60 每秒。
     *
     * @return 允许返回剩余令牌；拒绝返回 -1
     */
    public long tryAcquireRequest(String virtualKeyId, Integer rpmLimit) {
        if (rpmLimit == null || rpmLimit <= 0) {
            return Long.MAX_VALUE;
        }
        long capacity = Math.min(rpmLimit, capacityScale);
        TokenBucket bucket = buckets.computeIfAbsent(String.valueOf(virtualKeyId),
                k -> new TokenBucket(capacity, capacity / 60.0d, null));
        return bucket.tryConsume(1);
    }

    /** 桶数（观测） */
    public int bucketCount() {
        return buckets.size();
    }

    /** 供测试：注入确定性时钟的桶（覆盖注册表内桶） */
    void putBucketForTest(String key, TokenBucket bucket) {
        buckets.put(key, bucket);
    }

    /** 供测试：清空 */
    void clear() {
        buckets.clear();
    }

    /** 供测试：直读 */
    Map<String, TokenBucket> buckets() {
        return buckets;
    }
}

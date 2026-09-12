package cn.chyuan.ai.domain.governance.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 令牌桶单测（工单 0225 AD6）：容量突发/时间回填/速率边界。
 */
class TokenBucketTest {

    @Test
    void 突发与耗尽() {
        AtomicLong clock = new AtomicLong(0);
        TokenBucket bucket = new TokenBucket(5, 1.0d, clock::get);
        // 满桶允许一次突发（返回消费后余量）
        assertEquals(0, bucket.tryConsume(5), "满桶允许一次突发");
        assertEquals(-1, bucket.tryConsume(1), "耗尽后拒绝");
        assertEquals(0, bucket.available());
    }

    @Test
    void 时间回填与速率() {
        AtomicLong clock = new AtomicLong(0);
        // 容量 10、速率 10/s：耗尽后 1 秒回填 10 个
        TokenBucket bucket = new TokenBucket(10, 10.0d, clock::get);
        assertEquals(0, bucket.tryConsume(10));
        clock.addAndGet(500_000_000L); // 0.5s → 回填 5
        assertEquals(5, bucket.available());
        assertEquals(0, bucket.tryConsume(5));
        assertEquals(-1, bucket.tryConsume(1));
        // nanosToRefillOne：空桶等待约 0.1s
        long nanos = bucket.nanosToRefillOne();
        assertTrue(nanos > 0 && nanos <= 100_000_000L, "等待纳秒 " + nanos);
    }

    @Test
    void 脏值钳制与注册表() {
        // 容量/速率脏值回退
        TokenBucket bucket = new TokenBucket(0, -1, () -> 0L);
        assertEquals(1, bucket.capacity());
        assertEquals(-1, bucket.tryConsume(2));
        TokenBucketRegistry registry = new TokenBucketRegistry();
        // 未启用：registry 不接管（QuotaService 判 enabled()）
        assertTrue(!registry.enabled());
        // tryAcquireRequest：rpm 空 = 不限
        assertEquals(Long.MAX_VALUE, registry.tryAcquireRequest("vk-1", null));
        // rpm 有限：前 3 次放行第 4 次拒绝（capacity=min(rpm,100)）
        assertEquals(2, registry.tryAcquireRequest("vk-2", 3));
        assertEquals(1, registry.tryAcquireRequest("vk-2", 3));
        assertEquals(0, registry.tryAcquireRequest("vk-2", 3));
        assertEquals(-1, registry.tryAcquireRequest("vk-2", 3));
        assertEquals(1, registry.bucketCount());
    }
}

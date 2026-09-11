package cn.chyuan.ai.domain.llmchannel.service;

import cn.chyuan.ai.domain.llmchannel.model.valobj.LlmChannelVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 渠道并发闸（工单 0161，one-api 渠道并发口径裁剪）
 *
 * <p>每渠道独立 {@link Semaphore}（公平排队），获取带排队超时（超时由调用方按 -32022 拒绝）；
 * 限额 0/空=不限制（默认，与现状一致）；单实例口径——跨实例权威并发归配额 Redis 桶（0019）。
 * 活跃并发经 gauge channel_active_requests{channel} 暴露（registry 缺席静默跳过）。
 * tryAcquire/release 严格配对（调用方 try/finally 保证释放）。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class ChannelConcurrencyGuard {

    /** 获取排队超时毫秒（渠道满并发时的等待上限） */
    @Value("${governance.channel.concurrency-queue-ms:1000}")
    private long queueTimeoutMs;

    /** channelId → 信号量（限额可随配置热变，重建即生效） */
    private final Map<Long, Semaphore> semaphores = new ConcurrentHashMap<>();

    /** channelId → 活跃在途计数（gauge 数据面） */
    private final Map<Long, AtomicLong> active = new ConcurrentHashMap<>();

    private final org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistryProvider;

    public ChannelConcurrencyGuard(
            org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistryProvider) {
        this.meterRegistryProvider = meterRegistryProvider;
    }

    /** 渠道并发限额是否生效（max_concurrency 空/<=0=不限制） */
    public static boolean limited(LlmChannelVO channel) {
        return channel != null && channel.getMaxConcurrency() != null && channel.getMaxConcurrency() > 0;
    }

    /**
     * 尝试占位：不配置=不限恒真；配置后带排队超时获取，超时返回 false（调用方 -32022 拒绝）。
     */
    public boolean tryAcquire(LlmChannelVO channel) {
        if (!limited(channel)) {
            return true;
        }
        Semaphore semaphore = semaphoreOf(channel);
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(queueTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (acquired) {
            activeOf(channel).incrementAndGet();
        }
        return acquired;
    }

    /** 释放占位（与 tryAcquire 配对；幂等下界 0） */
    public void release(LlmChannelVO channel) {
        if (!limited(channel)) {
            return;
        }
        semaphoreOf(channel).release();
        AtomicLong counter = active.get(channel.getId());
        if (counter != null) {
            long now = counter.decrementAndGet();
            if (now < 0) {
                counter.compareAndSet(now, 0);
            }
        }
    }

    /** 当前在途（观测/测试用） */
    public int inFlight(long channelId) {
        AtomicLong counter = active.get(channelId);
        return counter == null ? 0 : (int) Math.max(0, counter.get());
    }

    private Semaphore semaphoreOf(LlmChannelVO channel) {
        return semaphores.computeIfAbsent(channel.getId(),
                id -> new Semaphore(Math.max(1, channel.getMaxConcurrency()), true));
    }

    /** 活跃计数 + gauge 注册（首次触碰注册一次；registry 缺席仅计数） */
    private AtomicLong activeOf(LlmChannelVO channel) {
        AtomicLong counter = active.computeIfAbsent(channel.getId(), id -> new AtomicLong());
        io.micrometer.core.instrument.MeterRegistry registry =
                meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();
        if (registry != null && registry.find("channel_active_requests")
                .tag("channel", channel.getName()).gauge() == null) {
            registry.gauge("channel_active_requests",
                    java.util.List.of(io.micrometer.core.instrument.Tag.of("channel", channel.getName())),
                    counter);
        }
        return counter;
    }
}

package cn.chyuan.ai.infrastructure.health;

import cn.chyuan.ai.domain.llmchannel.service.ChannelHealthService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 渠道健康分定时采样器（工单 0159，ChannelHealthPatrol 同款轻量线程口径）
 *
 * <p>周期调用 {@link ChannelHealthService#sampleAll()} 落 mcp_channel_health_snapshot；
 * 采样周期 governance.channel.health.sample-seconds（<=0 关闭，默认关闭——观测按需开启）；
 * DB 异常静默退避不影响请求面。
 *
 * @author chyuan
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "governance.channel.health", name = "sample-seconds")
public class ChannelHealthSampler {

    @Resource
    private ChannelHealthService channelHealthService;

    /** 采样周期秒（<=0 关闭） */
    @Value("${governance.channel.health.sample-seconds:0}")
    private int sampleSeconds;

    private ScheduledExecutorService scheduler;

    @PostConstruct
    void start() {
        if (sampleSeconds <= 0) {
            log.info("[health-sampler] 采样周期 <=0，健康分采样关闭");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "channel-health-sampler");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::sampleQuietly,
                sampleSeconds, sampleSeconds, TimeUnit.SECONDS);
        log.info("[health-sampler] 渠道健康分采样已启动 周期={}s", sampleSeconds);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** 采样入口（定时与测试共用；异常静默退避） */
    public void sampleQuietly() {
        try {
            int saved = channelHealthService.sampleAll();
            log.debug("[health-sampler] 快照采样完成 落库={} 条", saved);
        } catch (Exception e) {
            log.warn("[health-sampler] 健康分采样失败（静默退避）：{}", e.getMessage());
        }
    }
}

package cn.chyuan.ai.infrastructure.externalattach;

import cn.chyuan.ai.domain.externalattach.adapter.port.IExternalMcpAttachPort;
import cn.chyuan.ai.domain.externalattach.adapter.repository.IExternalAttachRepository;
import cn.chyuan.ai.domain.externalattach.model.valobj.ExternalAttachVO;
import cn.chyuan.ai.domain.governance.adapter.IGovernanceEventPublisher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 渠道定时健康巡检（工单 0058，one-api 轻量巡检闭环口径）
 *
 * <p>周期探测启用/自动禁用态渠道（手动禁用跳过）：记录 test_time 与 response_time_ms；
 * 启用态连续失败达阈值 → 自动禁用（AUTO_DISABLED）+ 冷却期 + CHANNEL_AUTO_DISABLED 事件；
 * 自动禁用态探测成功一次 → 恢复启用 + 清计数 + CHANNEL_RECOVERED 事件（半开恢复，0059 被动熔断共用）。
 * 巡检线程独立有界；DB/Redis 异常静默退避不影响请求面。
 *
 * @author chyuan
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mcp.external.attach.patrol-seconds", matchIfMissing = true)
public class ChannelHealthPatrol {

    /** 渠道自动禁用事件（0051 webhook 订阅口径） */
    public static final String EVENT_CHANNEL_AUTO_DISABLED = "CHANNEL_AUTO_DISABLED";

    /** 渠道恢复事件 */
    public static final String EVENT_CHANNEL_RECOVERED = "CHANNEL_RECOVERED";

    @Resource
    private IExternalAttachRepository attachRepository;

    /** 巡检端口缺席时跳过（切片上下文防御） */
    @Resource
    private IExternalMcpAttachPort attachPort;

    @Resource
    private IGovernanceEventPublisher eventPublisher;

    /** 渠道状态指标（工单 0067） */
    @Resource
    private cn.chyuan.ai.infrastructure.utils.GatewayMetrics gatewayMetrics;

    /** 巡检周期秒（0/缺省走默认；>0 生效） */
    @Value("${mcp.external.attach.patrol-seconds:60}")
    private long patrolSeconds;

    /** 连续失败自动禁用阈值 */
    @Value("${mcp.external.attach.patrol-fail-threshold:3}")
    private int failThreshold;

    /** 自动禁用冷却秒（冷却结束仍需探测成功才恢复） */
    @Value("${mcp.external.attach.cooldown-seconds:120}")
    private long cooldownSeconds;

    /** attachId → 连续失败计数（内存态，成功清零） */
    private final ConcurrentHashMap<Long, Integer> consecutiveFails = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "channel-health-patrol");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean started = false;

    @PostConstruct
    public void autoStart() {
        ensureStarted();
    }

    /** 惰性启动（首次访问或容器就绪后；重复调用幂等） */
    public synchronized void ensureStarted() {
        if (started || patrolSeconds <= 0) {
            return;
        }
        started = true;
        scheduler.scheduleWithFixedDelay(this::patrolOnceSafely, patrolSeconds, patrolSeconds, TimeUnit.SECONDS);
        log.info("渠道健康巡检已启动：周期 {}s，失败阈值 {}，冷却 {}s", patrolSeconds, failThreshold, cooldownSeconds);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    /** 单轮巡检（异常不外抛，供调度与测试共用） */
    public void patrolOnceSafely() {
        try {
            patrolOnce();
        } catch (Exception e) {
            log.warn("渠道巡检轮异常（退避到下轮）：{}", e.getMessage());
        }
    }

    void patrolOnce() {
        List<ExternalAttachVO> attaches = attachRepository.findAllAttaches();
        for (ExternalAttachVO attach : attaches) {
            if (attach.getStatus() == null
                    || attach.getStatus() == ExternalAttachVO.STATUS_MANUAL_DISABLED) {
                continue; // 手动禁用不巡检、不被自动恢复
            }
            patrolChannel(attach);
        }
    }

    private void patrolChannel(ExternalAttachVO attach) {
        long start = System.currentTimeMillis();
        IExternalMcpAttachPort.ConnectState state;
        try {
            state = attachPort.probeConnect(attach);
        } catch (Exception e) {
            state = new IExternalMcpAttachPort.ConnectState(false, 0, e.getMessage());
        }
        long cost = System.currentTimeMillis() - start;

        try {
            if (state.connected()) {
                onProbeSuccess(attach, cost);
            } else {
                onProbeFailure(attach, state.error());
            }
        } catch (Exception e) {
            log.warn("巡检状态回写失败 attach={}：{}", attach.getAttachName(), e.getMessage());
        }
    }

    private void onProbeSuccess(ExternalAttachVO attach, long cost) {
        consecutiveFails.remove(attach.getId());
        attachRepository.updateChannelHealth(attach.getId(), cost);
        if (attach.getStatus() == ExternalAttachVO.STATUS_AUTO_DISABLED) {
            // 半开恢复：探测成功一次即恢复启用并清计数
            attachRepository.updateChannelStatus(attach.getId(), ExternalAttachVO.STATUS_ENABLED, null);
            if (gatewayMetrics != null) {
                gatewayMetrics.channelState(attach.getAttachName(), 0);
            }
            publish(EVENT_CHANNEL_RECOVERED, attach, "探测成功自动恢复");
            log.info("渠道已恢复: gateway={} attach={} 耗时{}ms", attach.getGatewayId(), attach.getAttachName(), cost);
        }
    }

    private void onProbeFailure(ExternalAttachVO attach, String error) {
        if (attach.getStatus() == ExternalAttachVO.STATUS_AUTO_DISABLED) {
            return; // 已自动禁用，保持等待恢复探测
        }
        int fails = consecutiveFails.merge(attach.getId(), 1, Integer::sum);
        if (fails >= failThreshold) {
            consecutiveFails.remove(attach.getId());
            Date cooldownUntil = new Date(System.currentTimeMillis() + cooldownSeconds * 1000);
            attachRepository.updateChannelStatus(attach.getId(), ExternalAttachVO.STATUS_AUTO_DISABLED, cooldownUntil);
            if (gatewayMetrics != null) {
                gatewayMetrics.channelState(attach.getAttachName(), 1);
            }
            publish(EVENT_CHANNEL_AUTO_DISABLED, attach, "连续失败 " + fails + " 次：" + abbreviate(error));
            log.warn("渠道自动禁用: gateway={} attach={} 连续失败{}次 冷却至{}", attach.getGatewayId(),
                    attach.getAttachName(), fails, cooldownUntil);
        }
    }

    private void publish(String type, ExternalAttachVO attach, String reason) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("gatewayId", attach.getGatewayId());
            payload.put("attachName", attach.getAttachName());
            payload.put("attachId", attach.getId());
            payload.put("reason", reason);
            eventPublisher.publish(type, payload);
        } catch (Exception e) {
            log.debug("巡检事件发布失败：{}", e.getMessage());
        }
    }

    private static String abbreviate(String error) {
        if (error == null) {
            return "";
        }
        return error.length() > 200 ? error.substring(0, 200) : error;
    }
}

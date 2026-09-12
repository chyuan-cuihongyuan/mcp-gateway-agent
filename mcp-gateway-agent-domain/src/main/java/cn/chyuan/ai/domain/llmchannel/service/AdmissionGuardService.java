package cn.chyuan.ai.domain.llmchannel.service;

import cn.chyuan.ai.domain.governance.service.AdaptiveGuard;
import cn.chyuan.ai.domain.governance.service.HotspotParamLimiter;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import cn.chyuan.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 准入守卫（五期 AD 簇 0226/0227 统一挂点，全部默认关=零行为变化）—
 * AD7 热点参数限流：按模型/虚拟 Key 维度滑动窗口计数，超阈 -32009 快速失败；
 * AD8 水位自适应：并发使用率 + 排队深度 → GREEN/YELLOW/RED 三档，
 * RED 拒新（-32016）、YELLOW 对半放行（确定性降额）、GREEN 放行。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class AdmissionGuardService {

    @Value("${governance.hotspot.enabled:false}")
    private boolean hotspotEnabled;

    @Value("${governance.hotspot.window-ms:10000}")
    private long hotspotWindowMs = 10_000L;

    @Value("${governance.hotspot.model-limit:600}")
    private long hotspotModelLimit = 600;

    @Value("${governance.hotspot.vk-limit:3000}")
    private long hotspotVkLimit = 3_000L;

    @Value("${governance.adaptive.enabled:false}")
    private boolean adaptiveEnabled;

    @Value("${governance.adaptive.yellow-ratio:0.7}")
    private double yellowRatio = 0.7d;

    @Value("${governance.adaptive.red-ratio:0.9}")
    private double redRatio = 0.9d;

    private final ObjectProvider<ChannelConcurrencyGuard> concurrencyGuardProvider;
    private final HotspotParamLimiter modelHotspot;
    private final HotspotParamLimiter vkHotspot;
    private final AtomicLong admissionSeq = new AtomicLong();

    @Autowired
    public AdmissionGuardService(ObjectProvider<ChannelConcurrencyGuard> concurrencyGuardProvider) {
        this(concurrencyGuardProvider, new HotspotParamLimiter(10_000L, System::nanoTime),
                new HotspotParamLimiter(10_000L, System::nanoTime));
    }

    /** 测试注入：确定性热点限流器 */
    AdmissionGuardService(ObjectProvider<ChannelConcurrencyGuard> concurrencyGuardProvider,
            HotspotParamLimiter modelHotspot, HotspotParamLimiter vkHotspot) {
        this.concurrencyGuardProvider = concurrencyGuardProvider;
        this.modelHotspot = modelHotspot;
        this.vkHotspot = vkHotspot;
    }

    /**
     * 准入判定（chatCompletion 入口调用）：热点维度计数 → 水位分档。
     * 关闭时恒放行。窗口经 governance.hotspot.window-ms 生效（重启生效）。
     */
    public void assertAdmission(String model, String virtualKeyId,
            int inFlightTotal, int capacityTotal) {
        if (hotspotEnabled) {
            if (!modelHotspot.tryAcquire("model:" + model, hotspotModelLimit)
                    || !vkHotspot.tryAcquire("vk:" + virtualKeyId, hotspotVkLimit)) {
                log.warn("热点参数限流: model={} vk={} 维度超阈", model, virtualKeyId);
                throw new AppException(McpErrorCodes.QUOTA_EXCEEDED,
                        "热点参数超限，请稍后重试（模型或调用方维度限流）");
            }
        }
        if (adaptiveEnabled) {
            double ratio = capacityTotal <= 0 ? 0 : (double) inFlightTotal / capacityTotal;
            AdaptiveGuard.Thresholds thresholds = new AdaptiveGuard.Thresholds(yellowRatio, redRatio, 100);
            String level = AdaptiveGuard.grade(new AdaptiveGuard.LoadWatermark(ratio, 0), thresholds);
            long seq = admissionSeq.incrementAndGet();
            if (!AdaptiveGuard.shouldAdmit(level, seq)) {
                log.warn("水位自适应拒压: level={} inFlight={} capacity={} seq={}",
                        level, inFlightTotal, capacityTotal, seq);
                throw new AppException(McpErrorCodes.CONCURRENCY_EXCEEDED,
                        "系统负载水位过高（" + level + "），请求被拒压");
            }
        }
    }
}

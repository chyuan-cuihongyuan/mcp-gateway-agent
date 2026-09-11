package cn.chyuan.ai.domain.llmchannel.service;

import cn.chyuan.ai.domain.llmchannel.adapter.repository.IChannelHealthSnapshotRepository;
import cn.chyuan.ai.domain.llmchannel.model.valobj.ChannelHealthSnapshotVO;
import cn.chyuan.ai.domain.llmchannel.model.valobj.LlmChannelVO;
import cn.chyuan.ai.domain.usage.adapter.repository.IUsageRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 渠道健康分服务（工单 0159）
 *
 * <p>加权评分纯函数见 {@link ChannelHealthScore}；本服务薄适配数据面：
 * 错误率/平均延迟取自近 N 次账本（channelRecentStats），探测分从渠道最近连通性测试
 * 派生（test_time + response_time_ms 在窗口内 → 100；测过但无耗时 → 0；未测过 → 缺探测降级）。
 * 调度降权：低于阈值的渠道排候选尾（demoteBelow 纯函数，LlmChatService 消费）；
 * 快照采样：{@link #sampleAll()} 由 infrastructure 定时器周期调用落 mcp_channel_health_snapshot。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class ChannelHealthService {

    /** 权重配置（和=1 校验，装配期 fail-fast） */
    @Value("${governance.channel.health.weight-error:0.5}")
    private double weightError;

    @Value("${governance.channel.health.weight-probe:0.2}")
    private double weightProbe;

    @Value("${governance.channel.health.weight-latency:0.3}")
    private double weightLatency;

    /** 参考延迟毫秒：平均延迟达到该值延迟分记 0 */
    @Value("${governance.channel.health.latency-ref-ms:5000}")
    private long latencyRefMs;

    /** 近 N 次账本窗口 */
    @Value("${governance.channel.health.recent-n:20}")
    private int recentN;

    /** 探测新鲜度窗口毫秒（test_time 超窗按缺探测降级） */
    @Value("${governance.channel.health.probe-fresh-ms:86400000}")
    private long probeFreshMs;

    @Resource
    private cn.chyuan.ai.domain.llmchannel.adapter.repository.ILlmChannelRepository channelRepository;

    /** 账本端口（切片测试上下文可缺省=错误/延迟无数据） */
    @Resource
    private org.springframework.beans.factory.ObjectProvider<IUsageRepository> usageRepositoryProvider;

    /** 快照仓储（切片测试上下文可缺省=采样跳过落库） */
    @Resource
    private org.springframework.beans.factory.ObjectProvider<IChannelHealthSnapshotRepository> snapshotRepositoryProvider;

    /** 装配期权重校验（和=1 fail-fast） */
    @jakarta.annotation.PostConstruct
    void initWeights() {
        ChannelHealthScore.validateWeights(weightError, weightProbe, weightLatency);
    }

    /**
     * 全渠道健康报表（admin 端点）：键 channelId/channelName/score/errorRate/probeScore/
     * avgLatencyMs/demoted（是否低于降权阈值）。
     */
    public List<Map<String, Object>> reports(int degradeThreshold) {
        ChannelHealthScore.Weights weights = ChannelHealthScore.validateWeights(
                weightError, weightProbe, weightLatency);
        List<Map<String, Object>> reports = new ArrayList<>();
        for (LlmChannelVO channel : channelRepository.findAll()) {
            Map<String, Object> stats = statsOf(channel.getName());
            int total = intOf(stats.get("total"));
            int failures = intOf(stats.get("failures"));
            Long avgLatency = longOf(stats.get("avgLatencyMs"));
            Double probeScore = probeScoreOf(channel);
            double score = ChannelHealthScore.score(total, failures, probeScore, avgLatency,
                    latencyRefMs, weights);
            Map<String, Object> report = new HashMap<>();
            report.put("channelId", channel.getId());
            report.put("channelName", channel.getName());
            report.put("score", Math.round(score * 10) / 10.0);
            report.put("errorRate", total <= 0 ? null : (double) failures / total);
            report.put("probeScore", probeScore);
            report.put("avgLatencyMs", avgLatency);
            report.put("demoted", degradeThreshold > 0 && score < degradeThreshold);
            reports.add(report);
        }
        return reports;
    }

    /**
     * 定时采样（快照表落库）：逐启用渠道算分落 mcp_channel_health_snapshot。
     *
     * @return 本次落库快照数
     */
    public int sampleAll() {
        IChannelHealthSnapshotRepository snapshotRepository =
                snapshotRepositoryProvider == null ? null : snapshotRepositoryProvider.getIfAvailable();
        if (snapshotRepository == null) {
            return 0;
        }
        ChannelHealthScore.Weights weights = ChannelHealthScore.validateWeights(
                weightError, weightProbe, weightLatency);
        Date sampledAt = new Date();
        int saved = 0;
        for (LlmChannelVO channel : channelRepository.findAll()) {
            try {
                Map<String, Object> stats = statsOf(channel.getName());
                int total = intOf(stats.get("total"));
                int failures = intOf(stats.get("failures"));
                Long avgLatency = longOf(stats.get("avgLatencyMs"));
                Double probeScore = probeScoreOf(channel);
                double score = ChannelHealthScore.score(total, failures, probeScore, avgLatency,
                        latencyRefMs, weights);
                snapshotRepository.insert(ChannelHealthSnapshotVO.builder()
                        .channelId(channel.getId())
                        .channelName(channel.getName())
                        .score(Math.round(score * 10) / 10.0)
                        .errorRate(total <= 0 ? null : (double) failures / total)
                        .probeScore(probeScore)
                        .avgLatencyMs(avgLatency)
                        .sampledAt(sampledAt)
                        .build());
                saved++;
            } catch (Exception e) {
                log.warn("健康分快照采样失败 channel={}：{}", channel.getName(), e.getMessage());
            }
        }
        return saved;
    }

    /**
     * 调度降权（工单 0159）：低于阈值的渠道排候选尾（纯函数委托；阈值 <=0 关闭）。
     * LlmChatService 调度前调用。
     */
    public List<LlmChannelVO> demote(List<LlmChannelVO> candidates, int threshold) {
        return ChannelHealthScore.demoteBelow(candidates, LlmChannelVO::getId, this::scoreOfId, threshold);
    }

    /** 单渠道当前分（降权排序用；异常或数据缺失按满分=不降权，失败兜底） */
    private double scoreOfId(long channelId) {
        try {
            LlmChannelVO channel = channelRepository.findById(channelId);
            if (channel == null) {
                return 100.0;
            }
            Map<String, Object> stats = statsOf(channel.getName());
            int total = intOf(stats.get("total"));
            int failures = intOf(stats.get("failures"));
            return ChannelHealthScore.score(total, failures, probeScoreOf(channel),
                    longOf(stats.get("avgLatencyMs")), latencyRefMs,
                    ChannelHealthScore.validateWeights(weightError, weightProbe, weightLatency));
        } catch (Exception e) {
            log.debug("健康分计算失败 channelId={}：{}", channelId, e.getMessage());
            return 100.0;
        }
    }

    /** 近 N 次账本统计（账本缺席=空统计） */
    private Map<String, Object> statsOf(String channelName) {
        IUsageRepository usageRepository =
                usageRepositoryProvider == null ? null : usageRepositoryProvider.getIfAvailable();
        if (usageRepository == null) {
            return new HashMap<>();
        }
        try {
            return usageRepository.channelRecentStats(channelName, recentN);
        } catch (Exception e) {
            log.debug("渠道账本统计失败 channel={}：{}", channelName, e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * 探测分派生（工单 0159）：test_time 在新鲜度窗口内且 response_time_ms > 0 → 100；
     * 测过但无耗时/超窗 → 0；从未测试 → null（缺探测项降级，纯函数按 50 中性计入）。
     */
    private Double probeScoreOf(LlmChannelVO channel) {
        if (channel == null || channel.getTestTime() == null) {
            return null;
        }
        boolean fresh = System.currentTimeMillis() - channel.getTestTime().getTime() <= probeFreshMs;
        if (!fresh) {
            return null;
        }
        return channel.getResponseTimeMs() != null && channel.getResponseTimeMs() > 0 ? 100.0 : 0.0;
    }

    private static int intOf(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    private static Long longOf(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }
}

package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.usage.adapter.repository.IUsageRepository;
import cn.chyuan.ai.domain.usage.model.valobj.DailyUsageVO;
import cn.chyuan.ai.domain.usage.model.valobj.UsageQueryVO;
import cn.chyuan.ai.domain.usage.model.valobj.UsageRecordVO;
import cn.chyuan.ai.infrastructure.dao.IUsageDao;
import cn.chyuan.ai.infrastructure.dao.po.McpUsageDailyPO;
import cn.chyuan.ai.infrastructure.dao.po.McpUsageLogPO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用量账本仓储实现（工单 0046）
 *
 * @author chyuan
 */
@Repository
public class UsageRepository implements IUsageRepository {

    @Resource
    private IUsageDao usageDao;

    @Override
    public void insert(UsageRecordVO record) {
        McpUsageLogPO po = McpUsageLogPO.builder()
                .requestId(record.getRequestId())
                .virtualKeyId(record.getVirtualKeyId() == null ? 0L : record.getVirtualKeyId())
                .apiKeyHash(record.getApiKeyHash())
                .gatewayId(record.getGatewayId())
                .trafficType(record.getTrafficType())
                .toolOrModel(record.getToolOrModel() == null ? "" : record.getToolOrModel())
                .channelId(record.getChannelId() == null ? "" : record.getChannelId())
                .status(record.getStatus())
                .durationMs(record.getDurationMs())
                .promptTokens(record.getPromptTokens())
                .completionTokens(record.getCompletionTokens())
                .cost(record.getCost())
                .tags(record.getTags())
                .cacheHit(record.getCacheHit())
                .clientIp(record.getClientIp())
                .sessionId(record.getSessionId())
                .createdAt(record.getCreatedAt())
                .build();
        usageDao.insertLog(po);
    }

    @Override
    public void upsertDaily(DailyUsageVO delta) {
        usageDao.upsertDaily(McpUsageDailyPO.builder()
                .statDate(delta.getStatDate())
                .virtualKeyId(delta.getVirtualKeyId() == null ? 0L : delta.getVirtualKeyId())
                .toolOrModel(delta.getToolOrModel() == null ? "" : delta.getToolOrModel())
                .channelId(delta.getChannelId() == null ? "" : delta.getChannelId())
                .callCount(delta.getCallCount())
                .failCount(delta.getFailCount())
                .totalDurationMs(delta.getTotalDurationMs())
                .tokenSum(delta.getTokenSum())
                .build());
    }

    @Override
    public List<UsageRecordVO> page(UsageQueryVO query, int offset, int size) {
        Map<String, Object> params = queryParams(query);
        params.put("limitStart", offset);
        params.put("limitCount", size);
        return usageDao.pageLogs(params).stream().map(this::toVo).toList();
    }

    @Override
    public long count(UsageQueryVO query) {
        Long count = usageDao.countLogs(queryParams(query));
        return count == null ? 0 : count;
    }

    @Override
    public List<DailyUsageVO> dailyDetail(String fromDate, String toDate) {
        return usageDao.dailyDetail(fromDate, toDate).stream().map(this::toDailyVo).toList();
    }

    @Override
    public List<DailyUsageVO> dailyTotals(String fromDate, String toDate) {
        return usageDao.dailyTotals(fromDate, toDate).stream().map(this::toDailyVo).toList();
    }

    @Override
    public List<Map<String, Object>> tagDaily(String tag, String fromDate, String toDate) {
        return usageDao.tagDaily(tag, fromDate, toDate);
    }

    @Override
    public java.math.BigDecimal sumCostSince(Long virtualKeyId, java.util.Date since) {
        java.math.BigDecimal sum = usageDao.sumCostSince(virtualKeyId, since);
        return sum == null ? java.math.BigDecimal.ZERO : sum;
    }

    @Override
    public long sumTokensSince(Long virtualKeyId, java.util.Date since) {
        Long sum = usageDao.sumTokensSince(virtualKeyId, since);
        return sum == null ? 0L : sum;
    }

    @Override
    public long countSince(Long virtualKeyId, java.util.Date since) {
        Long count = usageDao.countSince(virtualKeyId, since);
        return count == null ? 0L : count;
    }

    @Override
    public List<Map<String, Object>> costDaily(String fromDate, String toDate) {
        return usageDao.costDaily(fromDate, toDate);
    }

    @Override
    public List<Map<String, Object>> costTopN(String dimension, String fromDate, String toDate, int top) {
        return usageDao.costTopN(dimension, fromDate, toDate, top);
    }

    @Override
    public Map<String, Object> unpricedStats(String fromDate, String toDate) {
        Map<String, Object> stats = usageDao.unpricedStats(fromDate, toDate);
        return stats == null ? Map.of("total", 0L, "unpriced", 0L) : stats;
    }

    @Override
    public List<Map<String, Object>> billingRows(String fromDate, String toDate, Long virtualKeyId, String tag) {
        return usageDao.billingRows(fromDate, toDate, virtualKeyId, tag);
    }

    @Override
    public Map<String, Object> cacheHitStats(String fromDate, String toDate) {
        Map<String, Object> stats = usageDao.cacheHitStats(fromDate, toDate);
        return stats == null ? Map.of("total", 0L, "hits", 0L) : stats;
    }

    private Map<String, Object> queryParams(UsageQueryVO query) {
        Map<String, Object> params = new HashMap<>();
        if (query != null) {
            params.put("fromDate", query.getFromDate());
            params.put("toDate", query.getToDate());
            params.put("virtualKeyId", query.getVirtualKeyId());
            params.put("toolOrModel", blankToNull(query.getToolOrModel()));
            params.put("status", blankToNull(query.getStatus()));
            params.put("trafficType", blankToNull(query.getTrafficType()));
            params.put("channelId", blankToNull(query.getChannelId()));
            params.put("tag", blankToNull(query.getTag()));
        }
        return params;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private UsageRecordVO toVo(McpUsageLogPO po) {
        return UsageRecordVO.builder()
                .requestId(po.getRequestId())
                .virtualKeyId(po.getVirtualKeyId() == null || po.getVirtualKeyId() == 0 ? null : po.getVirtualKeyId())
                .apiKeyHash(po.getApiKeyHash())
                .gatewayId(po.getGatewayId())
                .trafficType(po.getTrafficType())
                .toolOrModel(po.getToolOrModel())
                .channelId(po.getChannelId())
                .status(po.getStatus())
                .durationMs(po.getDurationMs())
                .promptTokens(po.getPromptTokens())
                .completionTokens(po.getCompletionTokens())
                .cost(po.getCost())
                .tags(po.getTags())
                .cacheHit(po.getCacheHit())
                .clientIp(po.getClientIp())
                .sessionId(po.getSessionId())
                .createdAt(po.getCreatedAt())
                .build();
    }

    private DailyUsageVO toDailyVo(McpUsageDailyPO po) {
        return DailyUsageVO.builder()
                .statDate(po.getStatDate())
                .virtualKeyId(po.getVirtualKeyId())
                .toolOrModel(po.getToolOrModel())
                .channelId(po.getChannelId())
                .callCount(po.getCallCount())
                .failCount(po.getFailCount())
                .totalDurationMs(po.getTotalDurationMs())
                .tokenSum(po.getTokenSum())
                .build();
    }
}

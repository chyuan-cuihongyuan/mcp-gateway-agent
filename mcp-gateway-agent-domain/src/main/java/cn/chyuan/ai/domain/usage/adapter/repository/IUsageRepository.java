package cn.chyuan.ai.domain.usage.adapter.repository;

import cn.chyuan.ai.domain.usage.model.valobj.DailyUsageVO;
import cn.chyuan.ai.domain.usage.model.valobj.UsageQueryVO;
import cn.chyuan.ai.domain.usage.model.valobj.UsageRecordVO;

import java.util.List;

/**
 * 用量账本仓储端口（工单 0046；domain 不引框架，infrastructure 落地 MyBatis）
 *
 * @author chyuan
 */
public interface IUsageRepository {

    /** 明细插入 */
    void insert(UsageRecordVO record);

    /**
     * 日聚合增量 upsert（唯一键 stat_date×virtual_key_id×tool_or_model×channel_id，
     * 冲突时 call_count/fail_count/total_duration_ms/token_sum 累加）
     */
    void upsertDaily(DailyUsageVO delta);

    /** 明细分页（倒序） */
    List<UsageRecordVO> page(UsageQueryVO query, int offset, int size);

    long count(UsageQueryVO query);

    /** 日聚合明细行（区间内，按日期升序） */
    List<DailyUsageVO> dailyDetail(String fromDate, String toDate);

    /** 按日期汇总（仪表盘趋势：callCount/failCount/tokenSum/totalDurationMs 按日合计） */
    List<DailyUsageVO> dailyTotals(String fromDate, String toDate);

    /** 按标签日聚合（工单 0088：键 statDate/callCount/failCount/costSum） */
    java.util.List<java.util.Map<String, Object>> tagDaily(String tag, String fromDate, String toDate);

    /** 窗口内成本合计（工单 0087 金额预算派生；空窗口返回 0） */
    java.math.BigDecimal sumCostSince(Long virtualKeyId, java.util.Date since);

    /** 成本日趋势（工单 0089：键 statDate/callCount/costSum） */
    java.util.List<java.util.Map<String, Object>> costDaily(String fromDate, String toDate);

    /** 成本 TopN（工单 0089：dimension=model|tag；键 dim/callCount/costSum） */
    java.util.List<java.util.Map<String, Object>> costTopN(String dimension, String fromDate, String toDate, int top);

    /** 未定价占比（工单 0089：键 total/unpriced） */
    java.util.Map<String, Object> unpricedStats(String fromDate, String toDate);

    /** 账单导出分组行（工单 0090：日×密钥×模型） */
    java.util.List<java.util.Map<String, Object>> billingRows(String fromDate, String toDate,
            Long virtualKeyId, String tag);

    /** 缓存命中率（工单 0099/0100：键 total/hits） */
    java.util.Map<String, Object> cacheHitStats(String fromDate, String toDate);
}

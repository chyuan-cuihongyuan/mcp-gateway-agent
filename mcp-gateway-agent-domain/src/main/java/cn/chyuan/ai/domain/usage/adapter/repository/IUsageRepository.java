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
}

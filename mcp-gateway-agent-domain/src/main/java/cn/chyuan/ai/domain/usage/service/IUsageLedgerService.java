package cn.chyuan.ai.domain.usage.service;

import cn.chyuan.ai.domain.usage.model.valobj.DailyUsageVO;
import cn.chyuan.ai.domain.usage.model.valobj.UsageQueryVO;
import cn.chyuan.ai.domain.usage.model.valobj.UsageRecordVO;

import java.util.List;

/**
 * 用量账本服务端口（工单 0046）
 *
 * @author chyuan
 */
public interface IUsageLedgerService {

    /** 落一条用量（异步 fire-and-forget，失败仅告警不影响主链） */
    void record(UsageRecordVO record);

    /** 明细分页（admin API） */
    List<UsageRecordVO> page(UsageQueryVO query, int page, int size);

    long count(UsageQueryVO query);

    /** 日聚合明细（区间） */
    List<DailyUsageVO> dailyDetail(String fromDate, String toDate);

    /** 按日汇总（仪表盘趋势） */
    List<DailyUsageVO> dailyTotals(String fromDate, String toDate);
}

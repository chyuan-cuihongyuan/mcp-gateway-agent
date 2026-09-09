package cn.chyuan.ai.infrastructure.dao;

import cn.chyuan.ai.infrastructure.dao.po.McpUsageDailyPO;
import cn.chyuan.ai.infrastructure.dao.po.McpUsageLogPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 用量账本 DAO（工单 0046）
 */
@Mapper
public interface IUsageDao {

    int insertLog(McpUsageLogPO po);

    /** 日聚合增量 upsert（ON DUPLICATE KEY UPDATE 累加） */
    int upsertDaily(McpUsageDailyPO po);

    List<McpUsageLogPO> pageLogs(Map<String, Object> params);

    Long countLogs(Map<String, Object> params);

    List<McpUsageDailyPO> dailyDetail(@Param("fromDate") String fromDate, @Param("toDate") String toDate);

    /** 按日汇总行（group by stat_date） */
    List<McpUsageDailyPO> dailyTotals(@Param("fromDate") String fromDate, @Param("toDate") String toDate);

    /** 按标签日聚合（工单 0088：明细表按日期分组，含成本合计） */
    List<Map<String, Object>> tagDaily(@Param("tag") String tag,
            @Param("fromDate") String fromDate, @Param("toDate") String toDate);

    /** 窗口内成本合计（工单 0087 金额预算派生口径） */
    java.math.BigDecimal sumCostSince(@Param("virtualKeyId") Long virtualKeyId,
            @Param("since") java.util.Date since);

    /** 成本日趋势（工单 0089：键 statDate/callCount/costSum） */
    List<Map<String, Object>> costDaily(@Param("fromDate") String fromDate, @Param("toDate") String toDate);

    /** 成本 TopN（工单 0089：dimension=model|tag；键 dim/callCount/costSum） */
    List<Map<String, Object>> costTopN(@Param("dimension") String dimension,
            @Param("fromDate") String fromDate, @Param("toDate") String toDate, @Param("top") int top);

    /** 未定价占比（工单 0089：LLM 流量 cost IS NULL 行数与总行数） */
    Map<String, Object> unpricedStats(@Param("fromDate") String fromDate, @Param("toDate") String toDate);

    /** 账单导出分组行（工单 0090：日×密钥×模型；键 statDate/virtualKeyId/toolOrModel/callCount/promptTokens/completionTokens/costSum/tags） */
    List<Map<String, Object>> billingRows(@Param("fromDate") String fromDate, @Param("toDate") String toDate,
            @Param("virtualKeyId") Long virtualKeyId, @Param("tag") String tag);
}

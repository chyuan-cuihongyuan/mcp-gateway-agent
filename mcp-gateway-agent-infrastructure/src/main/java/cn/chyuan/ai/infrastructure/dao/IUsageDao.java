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
}

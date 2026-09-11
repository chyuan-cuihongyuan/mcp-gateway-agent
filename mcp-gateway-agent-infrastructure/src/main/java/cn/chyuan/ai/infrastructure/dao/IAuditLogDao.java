package cn.chyuan.ai.infrastructure.dao;

import cn.chyuan.ai.infrastructure.dao.po.McpAuditLogPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 治理面审计日志 DAO（工单 0017）
 */
@Mapper
public interface IAuditLogDao {

    int insert(McpAuditLogPO po);

    List<McpAuditLogPO> queryPage(McpAuditLogPO query);

    /** 按分型分组计数（工单 0186 Z3） */
    List<java.util.Map<String, Object>> statType(@Param("start") String start);

    /** 按操作者分组计数（工单 0186 Z3） */
    List<java.util.Map<String, Object>> statActor(@Param("start") String start);

    Long queryCount(McpAuditLogPO query);
}

package cn.chyuan.ai.infrastructure.dao;

import cn.chyuan.ai.infrastructure.dao.po.McpAuditLogPO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 治理面审计日志 DAO（工单 0017）
 */
@Mapper
public interface IAuditLogDao {

    int insert(McpAuditLogPO po);

    List<McpAuditLogPO> queryPage(McpAuditLogPO query);

    Long queryCount(McpAuditLogPO query);
}

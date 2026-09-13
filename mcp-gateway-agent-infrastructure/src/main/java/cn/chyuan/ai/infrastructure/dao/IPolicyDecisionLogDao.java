package cn.chyuan.ai.infrastructure.dao;

import cn.chyuan.ai.infrastructure.dao.po.McpPolicyDecisionLogPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 策略决策日志 DAO（工单 0265 AH5）
 */
@Mapper
public interface IPolicyDecisionLogDao {

    int insert(McpPolicyDecisionLogPO po);

    List<McpPolicyDecisionLogPO> query(@Param("decision") String decision,
            @Param("fromMs") Long fromMs, @Param("toMs") Long toMs,
            @Param("offset") int offset, @Param("limit") int limit);
}

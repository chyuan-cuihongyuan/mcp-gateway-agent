package cn.chyuan.ai.infrastructure.dao;

import cn.chyuan.ai.infrastructure.dao.po.McpRoutingRulePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * tag 路由规则 DAO（工单 0160）
 */
@Mapper
public interface IRoutingRuleDao {

    int insert(McpRoutingRulePO po);

    int update(McpRoutingRulePO po);

    int deleteById(@Param("id") Long id);

    McpRoutingRulePO queryById(@Param("id") Long id);

    McpRoutingRulePO queryByName(@Param("ruleName") String ruleName);

    List<McpRoutingRulePO> queryAll();

    List<McpRoutingRulePO> queryActive();
}

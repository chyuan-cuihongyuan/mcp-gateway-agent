package cn.chyuan.ai.infrastructure.dao;

import cn.chyuan.ai.infrastructure.dao.po.McpPolicyDefinitionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 策略定义 DAO（工单 0261 AH2）
 */
@Mapper
public interface IPolicyDefinitionDao {

    int insert(McpPolicyDefinitionPO po);

    int update(McpPolicyDefinitionPO po);

    int deleteById(@Param("id") Long id);

    McpPolicyDefinitionPO queryById(@Param("id") Long id);

    List<McpPolicyDefinitionPO> queryAll();
}

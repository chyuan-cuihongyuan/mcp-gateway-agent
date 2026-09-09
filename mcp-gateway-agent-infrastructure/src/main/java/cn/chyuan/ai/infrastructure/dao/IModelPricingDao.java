package cn.chyuan.ai.infrastructure.dao;

import cn.chyuan.ai.infrastructure.dao.po.McpModelPricingPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 模型计价 DAO（工单 0085）
 */
@Mapper
public interface IModelPricingDao {

    int insert(McpModelPricingPO po);

    int update(McpModelPricingPO po);

    int deleteById(@Param("id") Long id);

    List<McpModelPricingPO> queryAll();

    McpModelPricingPO queryByModel(@Param("model") String model);

    McpModelPricingPO queryById(@Param("id") Long id);
}

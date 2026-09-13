package cn.chyuan.ai.infrastructure.dao;

import cn.chyuan.ai.infrastructure.dao.po.McpModelCatalogPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 模型目录 DAO（工单 0281 AJ5）
 */
@Mapper
public interface IModelCatalogDao {

    int insert(McpModelCatalogPO po);

    int update(McpModelCatalogPO po);

    McpModelCatalogPO queryByModel(@Param("model") String model);

    List<McpModelCatalogPO> queryAll();
}

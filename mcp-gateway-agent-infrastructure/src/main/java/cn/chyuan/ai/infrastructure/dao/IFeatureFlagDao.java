package cn.chyuan.ai.infrastructure.dao;

import cn.chyuan.ai.infrastructure.dao.po.McpFeatureFlagPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 特性开关 DAO（工单 0178 Y2）：upsert 同 id 按 databaseId 成对分叉（见 XML）。
 */
@Mapper
public interface IFeatureFlagDao {

    int upsert(McpFeatureFlagPO po);

    McpFeatureFlagPO queryByKey(@Param("flagKey") String flagKey);

    List<McpFeatureFlagPO> queryAll();
}

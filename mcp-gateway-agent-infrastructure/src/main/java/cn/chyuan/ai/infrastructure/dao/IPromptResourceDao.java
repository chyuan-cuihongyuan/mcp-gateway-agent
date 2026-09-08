package cn.chyuan.ai.infrastructure.dao;

import cn.chyuan.ai.infrastructure.dao.po.McpPromptPO;
import cn.chyuan.ai.infrastructure.dao.po.McpResourcePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 网关本地 Prompt/Resource DAO（工单 0053）
 */
@Mapper
public interface IPromptResourceDao {

    int insertPrompt(McpPromptPO po);

    int updatePrompt(McpPromptPO po);

    int deletePrompt(@Param("id") Long id);

    McpPromptPO queryPromptById(@Param("id") Long id);

    McpPromptPO queryPrompt(@Param("gatewayId") String gatewayId, @Param("name") String name);

    List<McpPromptPO> queryPrompts(@Param("gatewayId") String gatewayId);

    int insertResource(McpResourcePO po);

    int updateResource(McpResourcePO po);

    int deleteResource(@Param("id") Long id);

    McpResourcePO queryResourceById(@Param("id") Long id);

    McpResourcePO queryResource(@Param("gatewayId") String gatewayId, @Param("uri") String uri);

    List<McpResourcePO> queryResources(@Param("gatewayId") String gatewayId);
}

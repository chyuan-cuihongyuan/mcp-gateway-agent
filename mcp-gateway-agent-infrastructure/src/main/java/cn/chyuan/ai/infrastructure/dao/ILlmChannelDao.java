package cn.chyuan.ai.infrastructure.dao;

import cn.chyuan.ai.infrastructure.dao.po.McpLlmChannelPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * LLM 渠道 DAO（工单 0063）
 */
@Mapper
public interface ILlmChannelDao {

    int insert(McpLlmChannelPO po);

    int update(McpLlmChannelPO po);

    int deleteById(@Param("id") Long id);

    McpLlmChannelPO queryById(@Param("id") Long id);

    McpLlmChannelPO queryByName(@Param("name") String name);

    List<McpLlmChannelPO> queryAll();

    List<McpLlmChannelPO> queryEnabled();
}

package cn.chyuan.ai.infrastructure.dao;

import cn.chyuan.ai.infrastructure.dao.po.McpPromptVersionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 提示版本 DAO（工单 0196 AA1）
 */
@Mapper
public interface IPromptVersionDao {

    int insert(McpPromptVersionPO po);

    int update(McpPromptVersionPO po);

    McpPromptVersionPO query(@Param("promptName") String promptName, @Param("version") Integer version);

    Integer queryMaxVersion(@Param("promptName") String promptName);

    List<McpPromptVersionPO> queryByName(@Param("promptName") String promptName);

    List<McpPromptVersionPO> queryAll();

    McpPromptVersionPO queryByLabel(@Param("promptName") String promptName, @Param("label") String label);

    int clearLabels(@Param("promptName") String promptName);
}

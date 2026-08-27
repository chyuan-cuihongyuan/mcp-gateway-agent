package cn.chyuan.ai.infrastructure.dao;

import cn.chyuan.ai.infrastructure.dao.po.McpVirtualKeyPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 虚拟密钥 DAO（工单 0017）
 */
@Mapper
public interface IVirtualKeyDao {

    int insert(McpVirtualKeyPO po);

    int updateStatus(@Param("id") Long id, @Param("status") String status);

    int updateMeta(McpVirtualKeyPO po);

    McpVirtualKeyPO queryById(@Param("id") Long id);

    McpVirtualKeyPO queryByHash(@Param("apiKeyHash") String apiKeyHash);

    List<McpVirtualKeyPO> queryPage(McpVirtualKeyPO query);

    Long queryCount(McpVirtualKeyPO query);

    /**
     * 存量迁移专用：存在同哈希密钥时跳过插入
     */
    int insertIgnore(@Param("po") McpVirtualKeyPO po);
}

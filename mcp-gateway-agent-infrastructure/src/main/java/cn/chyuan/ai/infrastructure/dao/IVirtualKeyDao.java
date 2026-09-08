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

    /** 最后活跃时间更新（认证命中去抖后调用，工单 0045） */
    int touchLastActive(@Param("id") Long id);

    /** 预算窗口惰性重置（工单 0050） */
    int resetBudgetWindow(@Param("id") Long id);

    /** 预算窗口已用递增（工单 0050） */
    int incrementBudgetUsed(@Param("id") Long id);

    /** 密钥轮换（工单 0049：新哈希+前代宽限，只保留一代；前代哈希由 SQL 自赋值） */
    int rotateKey(@Param("id") Long id, @Param("newKeyHash") String newKeyHash,
            @Param("graceUntil") java.util.Date graceUntil);
}

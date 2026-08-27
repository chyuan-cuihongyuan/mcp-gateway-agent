package cn.chyuan.ai.infrastructure.dao;

import cn.chyuan.ai.infrastructure.dao.po.McpAdminUserPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * admin 用户 DAO（工单 0017）
 */
@Mapper
public interface IAdminUserDao {

    McpAdminUserPO queryByUsername(@Param("username") String username);

    Integer countAll();

    int insert(McpAdminUserPO po);
}

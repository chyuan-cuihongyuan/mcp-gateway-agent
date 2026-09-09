package cn.chyuan.ai.infrastructure.dao;

import cn.chyuan.ai.infrastructure.dao.po.McpGuardrailPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 治理护栏 DAO（工单 0091）
 */
@Mapper
public interface IGuardrailDao {

    int insert(McpGuardrailPO po);

    int update(McpGuardrailPO po);

    int deleteById(@Param("id") Long id);

    List<McpGuardrailPO> queryAll();

    McpGuardrailPO queryByName(@Param("name") String name);

    McpGuardrailPO queryById(@Param("id") Long id);
}

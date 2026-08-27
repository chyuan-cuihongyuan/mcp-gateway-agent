package cn.chyuan.ai.infrastructure.dao;

import cn.chyuan.ai.infrastructure.dao.po.McpCelRulePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * CEL 规则 DAO（工单 0018）
 */
@Mapper
public interface ICelRuleDao {

    /** 新增（rule_name 唯一键冲突抛 DuplicateKeyException） */
    int insert(McpCelRulePO rule);

    /** 按 id 更新（ruleName/expression/scopeType/gatewayId/virtualKeyId/status） */
    int update(McpCelRulePO rule);

    int deleteById(@Param("id") Long id);

    List<McpCelRulePO> queryAllActive();

    McpCelRulePO queryById(@Param("id") Long id);

    List<McpCelRulePO> queryByPage(@Param("keyword") String keyword, @Param("offset") int offset,
            @Param("size") int size);

    Long count(@Param("keyword") String keyword);
}

package cn.chyuan.ai.infrastructure.dao;

import cn.chyuan.ai.infrastructure.dao.po.McpCelRuleTemplatePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * CEL 规则模板 DAO（工单 0057）
 */
@Mapper
public interface ICelRuleTemplateDao {

    int insert(McpCelRuleTemplatePO po);

    /** 内置种子专用：code 冲突跳过（幂等） */
    int insertIgnore(@Param("po") McpCelRuleTemplatePO po);

    int update(McpCelRuleTemplatePO po);

    int deleteById(@Param("id") Long id);

    McpCelRuleTemplatePO queryById(@Param("id") Long id);

    McpCelRuleTemplatePO queryByCode(@Param("code") String code);

    List<McpCelRuleTemplatePO> queryAll();
}

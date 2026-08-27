package cn.chyuan.ai.domain.governance.adapter.repository;

import cn.chyuan.ai.domain.governance.model.valobj.CelRuleVO;

import java.util.List;

/**
 * CEL 规则仓储端口（工单 0018）
 *
 * @author chyuan
 */
public interface ICelRuleRepository {

    /** 新增（rule_name 唯一冲突由实现抛异常） */
    void insert(CelRuleVO rule);

    /** 按 id 更新表达式/作用域/状态 */
    boolean update(CelRuleVO rule);

    /** 按 id 删除 */
    boolean deleteById(Long id);

    /** 查询全部启用（ACTIVE）规则（求值快照数据源） */
    List<CelRuleVO> findAllActive();

    /** 按 id 查询 */
    CelRuleVO findById(Long id);

    /** 关键字（规则名）分页 */
    List<CelRuleVO> findByPage(String keyword, int offset, int size);

    /** 关键字计数 */
    long count(String keyword);
}

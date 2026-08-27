package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.model.valobj.CelRuleVO;

import java.util.List;

/**
 * CEL 规则服务端口（工单 0018）
 *
 * @author chyuan
 */
public interface ICelRuleService {

    /** 校验表达式可编译且变量已声明；返回 null 表示合法，否则返回可读错误原因 */
    String validateExpression(String expression);

    /** 创建规则：保存前编译校验，非法表达式抛 AppException（附原因）；写审计 */
    CelRuleVO create(CelRuleVO rule);

    /** 更新规则：保存前编译校验；写审计并失效本实例规则快照 */
    CelRuleVO update(CelRuleVO rule);

    /** 删除规则；写审计并失效本实例规则快照 */
    void delete(Long id);

    CelRuleVO getById(Long id);

    List<CelRuleVO> page(String keyword, int page, int size);

    long count(String keyword);

    /**
     * 当前适用的已编译规则快照（30s TTL，写操作即时失效本实例；
     * 0011 决策②热更新机制）。
     */
    List<CompiledCelRule> activeRuleSnapshot();
}

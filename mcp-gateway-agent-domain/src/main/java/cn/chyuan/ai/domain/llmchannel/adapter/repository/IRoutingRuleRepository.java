package cn.chyuan.ai.domain.llmchannel.adapter.repository;

import cn.chyuan.ai.domain.llmchannel.model.valobj.RoutingRuleVO;

import java.util.List;

/**
 * tag 路由规则仓储端口（工单 0160）
 *
 * @author chyuan
 */
public interface IRoutingRuleRepository {

    Long insert(RoutingRuleVO rule);

    boolean update(RoutingRuleVO rule);

    boolean deleteById(Long id);

    RoutingRuleVO findById(Long id);

    RoutingRuleVO findByName(String ruleName);

    List<RoutingRuleVO> findAll();

    /** 启用态规则（请求路由用） */
    List<RoutingRuleVO> findActive();
}

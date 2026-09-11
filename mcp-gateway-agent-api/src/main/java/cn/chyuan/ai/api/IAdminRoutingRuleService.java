package cn.chyuan.ai.api;

import cn.chyuan.ai.api.dto.RoutingRuleResponseDTO;
import cn.chyuan.ai.api.dto.RoutingRuleUpsertRequestDTO;

import java.util.List;

/**
 * admin tag 路由规则服务接口（工单 0160）
 *
 * @author chyuan
 */
public interface IAdminRoutingRuleService {

    RoutingRuleResponseDTO createRule(RoutingRuleUpsertRequestDTO requestDTO);

    RoutingRuleResponseDTO updateRule(Long id, RoutingRuleUpsertRequestDTO requestDTO);

    void deleteRule(Long id);

    RoutingRuleResponseDTO getRule(Long id);

    List<RoutingRuleResponseDTO> listRules();
}

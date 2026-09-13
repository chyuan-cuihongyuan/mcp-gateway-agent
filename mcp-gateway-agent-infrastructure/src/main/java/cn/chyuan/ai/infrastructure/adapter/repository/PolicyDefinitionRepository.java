package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.policy.service.PolicyEngine;
import cn.chyuan.ai.domain.policy.service.PolicyEngine.PolicyStatement;
import cn.chyuan.ai.infrastructure.dao.IPolicyDefinitionDao;
import cn.chyuan.ai.infrastructure.dao.po.McpPolicyDefinitionPO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 策略定义仓储实现（工单 0261 AH2）：实现 {@link PolicyEngine.PolicyStore} 端口，
 * 经 MyBatis 落 mcp_policy_definition 表（双方言公共子集 SQL）。
 *
 * @author chyuan
 */
@Repository
public class PolicyDefinitionRepository implements PolicyEngine.PolicyStore {

    @Resource
    private IPolicyDefinitionDao dao;

    @Override
    public void insert(PolicyStatement statement) {
        dao.insert(toPo(statement));
    }

    @Override
    public void update(PolicyStatement statement) {
        dao.update(toPo(statement));
    }

    @Override
    public void deleteById(long id) {
        dao.deleteById(id);
    }

    @Override
    public PolicyStatement findById(long id) {
        return toDomain(dao.queryById(id));
    }

    @Override
    public List<PolicyStatement> listAll() {
        return dao.queryAll().stream().map(PolicyDefinitionRepository::toDomain).toList();
    }

    private static McpPolicyDefinitionPO toPo(PolicyStatement statement) {
        McpPolicyDefinitionPO po = new McpPolicyDefinitionPO();
        po.setId(statement.id());
        po.setName(statement.name());
        po.setSubPattern(statement.subPattern());
        po.setObjPattern(statement.objPattern());
        po.setActPattern(statement.actPattern());
        po.setConditionExpr(statement.conditionExpr());
        po.setEffect(statement.effect());
        po.setPriority(statement.priority());
        po.setEnabled(statement.enabled());
        po.setNote(statement.note());
        po.setOperator(statement.operator());
        po.setUpdateTime(new java.util.Date());
        return po;
    }

    private static PolicyStatement toDomain(McpPolicyDefinitionPO po) {
        if (po == null) {
            return null;
        }
        return new PolicyStatement(po.getId(), po.getName(), po.getSubPattern(), po.getObjPattern(),
                po.getActPattern(), po.getConditionExpr(), po.getEffect(),
                po.getPriority() == null ? 0 : po.getPriority(),
                !Boolean.FALSE.equals(po.getEnabled()), po.getNote(), po.getOperator());
    }
}

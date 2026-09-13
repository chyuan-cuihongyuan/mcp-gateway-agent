package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.policy.service.DecisionLogRecorder;
import cn.chyuan.ai.domain.policy.service.DecisionLogRecorder.DecisionLog;
import cn.chyuan.ai.infrastructure.dao.IPolicyDecisionLogDao;
import cn.chyuan.ai.infrastructure.dao.po.McpPolicyDecisionLogPO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 策略决策日志仓储实现（工单 0265 AH5）：实现 {@link DecisionLogRecorder.DecisionLogStore} 端口，
 * 经 MyBatis 落 mcp_policy_decision_log 表（双方言公共子集 SQL，limit 封顶 200）。
 *
 * @author chyuan
 */
@Repository
public class PolicyDecisionLogRepository implements DecisionLogRecorder.DecisionLogStore {

    private static final int MAX_LIMIT = 200;

    @Resource
    private IPolicyDecisionLogDao dao;

    @Override
    public void append(DecisionLog entry) {
        McpPolicyDecisionLogPO po = new McpPolicyDecisionLogPO();
        po.setAtMs(entry.atMs());
        po.setSubject(entry.subject());
        po.setObject(entry.object());
        po.setAction(entry.action());
        po.setDecision(entry.decision());
        po.setHitStatementNames(String.join(",", entry.hitStatementNames()));
        po.setCached(entry.cached());
        po.setCostMs(entry.costMs());
        po.setUpdateTime(new java.util.Date());
        dao.insert(po);
    }

    @Override
    public List<DecisionLog> query(String decision, Long fromMs, Long toMs, int offset, int limit) {
        int capped = Math.min(Math.max(limit, 1), MAX_LIMIT);
        return dao.query(decision, fromMs, toMs, Math.max(0, offset), capped).stream()
                .map(PolicyDecisionLogRepository::toDomain)
                .toList();
    }

    private static DecisionLog toDomain(McpPolicyDecisionLogPO po) {
        return new DecisionLog(po.getId() == null ? 0 : po.getId(),
                po.getAtMs() == null ? 0 : po.getAtMs(),
                po.getSubject(), po.getObject(), po.getAction(), po.getDecision(),
                po.getHitStatementNames() == null || po.getHitStatementNames().isBlank()
                        ? List.of() : List.of(po.getHitStatementNames().split(",")),
                Boolean.TRUE.equals(po.getCached()),
                po.getCostMs() == null ? 0 : po.getCostMs());
    }
}

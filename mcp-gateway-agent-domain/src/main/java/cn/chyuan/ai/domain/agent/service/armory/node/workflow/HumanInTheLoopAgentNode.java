package cn.chyuan.ai.domain.agent.service.armory.node.workflow;

import cn.chyuan.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.chyuan.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.chyuan.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LoopAgent;
import com.google.adk.agents.SequentialAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Human-in-the-Loop 工作流节点 — P0: 关键操作人工审批机制
 *
 * <h3>工作流拓扑</h3>
 * <pre>
 * SequentialAgent(hitl_workflow)
 * ├─ PreApprovalAgent (可选)
 * │    生成审批请求信息
 * ├─ ApprovalGateAgent
 * │    暂停执行，等待人工审批
 * │    beforeModelCallback: 检查 state["approval_status"]
 * │    - null → 创建审批请求，设置 status="pending"，escalate 暂停
 * │    - "pending" → 继续 escalate，等待审批
 * │    - "approved" → 清除状态，继续执行
 * │    - "rejected" → 触发退出或重规划
 * └─ PostApprovalAgent (可选)
 *      根据审批结果继续执行或重规划
 * </pre>
 *
 * <h3>审批机制</h3>
 * <ul>
 *   <li>通过 session state 存储审批状态（approval_status）</li>
 *   <li>审批请求信息存储在 state["approval_request"]</li>
 *   <li>外部系统（Web UI / IM / Email）通过 API 更新审批状态</li>
 *   <li>支持超时机制（approval_timeout_seconds）</li>
 * </ul>
 *
 * <h3>配置示例</h3>
 * <pre>
 * - type: hitl
 *   name: critical_operation_approval
 *   approval-channel: "web" | "feishu" | "email"
 *   approval-timeout-seconds: 300
 *   risk-level: "high" | "medium"
 *   sub-agents:
 *     - preApprovalAgent    # 可选：生成审批信息
 *     - approvalGateAgent   # 必须：审批门控
 *     - postApprovalAgent   # 可选：审批后处理
 * </pre>
 *
 * @author chyuan
 * @since 2026-06-15
 */
@Slf4j
@Service("humanInTheLoopAgentNode")
public class HumanInTheLoopAgentNode extends AbstractArmorySupport {

    /** HITL 工作流要求的最少子 agent 数：ApprovalGateAgent */
    private static final int MIN_SUB_AGENTS = 1;

    /** Session state key：审批状态 */
    private static final String STATE_KEY_APPROVAL_STATUS = "approval_status";

    /** Session state key：审批请求信息 */
    private static final String STATE_KEY_APPROVAL_REQUEST = "approval_request";

    /** 审批状态：待审批 */
    private static final String STATUS_PENDING = "pending";

    /** 审批状态：已批准 */
    private static final String STATUS_APPROVED = "approved";

    /** 审批状态：已拒绝 */
    private static final String STATUS_REJECTED = "rejected";

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("【P0】Ai Agent 装配操作 - HumanInTheLoopAgentNode（人工审批机制）");

        AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow = dynamicContext.getCurrentAgentWorkflow();
        List<String> subAgentNames = currentAgentWorkflow.getSubAgents();

        if (subAgentNames == null || subAgentNames.size() < MIN_SUB_AGENTS) {
            log.warn("HITL 工作流需要至少 {} 个子 agent（ApprovalGateAgent），当前: {}，降级为顺序执行",
                    MIN_SUB_AGENTS, subAgentNames == null ? 0 : subAgentNames.size());
            return buildFallbackSequential(requestParameter, currentAgentWorkflow, dynamicContext);
        }

        // 读取 HITL 配置
        String approvalChannel = currentAgentWorkflow.getApprovalChannel() != null
                ? currentAgentWorkflow.getApprovalChannel() : "web";
        int approvalTimeoutSeconds = currentAgentWorkflow.getApprovalTimeoutSeconds() != null
                ? currentAgentWorkflow.getApprovalTimeoutSeconds() : 300;
        String riskLevel = currentAgentWorkflow.getRiskLevel() != null
                ? currentAgentWorkflow.getRiskLevel() : "medium";

        // 验证所有子 agent 存在
        for (String agentName : subAgentNames) {
            BaseAgent agent = dynamicContext.getAgentGroup().get(agentName);
            if (agent == null) {
                log.error("HITL 工作流子 agent 缺失: {}，降级为顺序执行", agentName);
                return buildFallbackSequential(requestParameter, currentAgentWorkflow, dynamicContext);
            }
        }

        // 增强 ApprovalGateAgent（通常是第一个或专门的审批 agent）
        String approvalGateAgentName = subAgentNames.get(0);
        boolean gateEnhanced = dynamicContext.enhanceAgent(approvalGateAgentName,
                ctx -> AgenticWorkflowEnhancer.attachApprovalGate(
                        ctx, approvalChannel, approvalTimeoutSeconds, riskLevel));

        if (!gateEnhanced) {
            log.warn("ApprovalGateAgent[{}] 无 Builder 缓存，无法挂审批门控，退化为普通顺序节点",
                    approvalGateAgentName);
            return buildFallbackSequential(requestParameter, currentAgentWorkflow, dynamicContext);
        }

        // A2: 阶段级 OTel span 上报
        for (String agentName : subAgentNames) {
            dynamicContext.enhanceAgent(agentName,
                    ctx -> AgenticWorkflowEnhancer.attachSpanEmitter(ctx,
                            "hitl." + agentName, "HITL_" + agentName.toUpperCase(), null));
        }
        log.info("【A2 OTel】HITL[{}] {} 个子 agent 阶段 span 已挂载",
                currentAgentWorkflow.getName(), subAgentNames.size());

        // enhance 覆盖后重新取实例
        List<BaseAgent> enhancedAgents = subAgentNames.stream()
                .map(name -> dynamicContext.getAgentGroup().get(name))
                .toList();

        // 构建 HITL 工作流：使用 SequentialAgent 串行执行
        // 审批门控逻辑通过 beforeModelCallback 实现
        SequentialAgent hitlWorkflow = SequentialAgent.builder()
                .name(currentAgentWorkflow.getName())
                .description(currentAgentWorkflow.getDescription())
                .subAgents(enhancedAgents)
                .build();

        dynamicContext.getAgentGroup().put(currentAgentWorkflow.getName(), hitlWorkflow);

        log.info("HITL 工作流装配完成: name={}, approvalChannel={}, timeout={}s, riskLevel={}, subAgents={}, gateEnhanced={}",
                currentAgentWorkflow.getName(), approvalChannel, approvalTimeoutSeconds, riskLevel,
                subAgentNames, gateEnhanced);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(
            ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return getBean("agentWorkflowNode");
    }
}

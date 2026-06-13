package cn.chyuan.ai.domain.agent.service.armory.node.workflow;

import cn.chyuan.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.chyuan.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.chyuan.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.SequentialAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 反思工作流节点 — Phase 2: Reflection Agent 模式
 *
 * 反思工作流 = Actor（执行者）→ Critic（评估者）→ Reflector（反思改进者）
 *
 * 【设计思路】
 * - Actor: 执行具体任务（如 RAG 检索、AIOps 分析）
 * - Critic: 评估执行结果的质量（相关性、完整性、准确性）
 * - Reflector: 基于评估结果提供改进建议
 *
 * 【实现方式】
 * - 底层使用 SequentialAgent 串行执行三个子 Agent
 * - 通过 outputKey 机制传递评估结果和改进建议
 * - 配置示例见 super-agent.yml 中的 reflection 工作流
 *
 * @author chyuan
 * @since 2025-06-13
 */
@Slf4j
@Service("reflectionAgentNode")
public class ReflectionAgentNode extends AbstractArmorySupport {

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("【Phase 2】Ai Agent 装配操作 - ReflectionAgentNode");

        AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow = dynamicContext.getCurrentAgentWorkflow();

        List<String> subAgentNames = currentAgentWorkflow.getSubAgents();
        if (subAgentNames == null || subAgentNames.size() != 3) {
            log.warn("Reflection 工作流需要 3 个子 Agent（Actor、Critic、Reflector），当前配置: {}",
                    subAgentNames != null ? subAgentNames.size() : 0);
            // 不抛出异常，允许灵活配置
        }

        List<BaseAgent> subAgents = dynamicContext.queryAgentList(subAgentNames);

        // 使用 SequentialAgent 构建反思工作流
        // 流程：Actor → Critic → Reflector
        SequentialAgent reflectionAgent =
                SequentialAgent.builder()
                        .name(currentAgentWorkflow.getName())
                        .description(currentAgentWorkflow.getDescription())
                        .subAgents(subAgents)
                        .build();

        dynamicContext.getAgentGroup().put(currentAgentWorkflow.getName(), reflectionAgent);

        log.info("反思工作流装配完成: name={}, subAgents={}",
                currentAgentWorkflow.getName(), subAgentNames);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(
            ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return getBean("agentWorkflowNode");
    }
}

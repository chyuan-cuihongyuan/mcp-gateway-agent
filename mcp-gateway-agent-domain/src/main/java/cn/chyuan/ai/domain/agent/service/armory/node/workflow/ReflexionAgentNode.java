package cn.chyuan.ai.domain.agent.service.armory.node.workflow;

import cn.chyuan.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.chyuan.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.chyuan.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LoopAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 反思迭代工作流节点 — Phase 3: Reflexion Agent 模式
 *
 * Reflexion = Reflection + Memory
 * 迭代改进流程：Actor（执行）→ Reflector（反思）→ Historian（记录历史）→ 循环改进
 *
 * @author chyuan
 * @since 2025-06-13
 */
@Slf4j
@Service("reflexionAgentNode")
public class ReflexionAgentNode extends AbstractArmorySupport {

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("【Phase 3】Ai Agent 装配操作 - ReflexionAgentNode");

        AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow = dynamicContext.getCurrentAgentWorkflow();

        List<String> subAgentNames = currentAgentWorkflow.getSubAgents();
        if (subAgentNames == null || subAgentNames.isEmpty()) {
            log.warn("Reflexion 工作流需要至少 2 个子 Agent（Actor、Reflector），当前配置为空");
        } else if (subAgentNames.size() < 2) {
            log.warn("Reflexion 工作流建议配置 3 个子 Agent（Actor、Reflector、Historian），当前: {} 个", subAgentNames.size());
        }

        List<BaseAgent> subAgents = dynamicContext.queryAgentList(subAgentNames);

        LoopAgent reflexionAgent =
                LoopAgent.builder()
                        .name(currentAgentWorkflow.getName())
                        .description(currentAgentWorkflow.getDescription())
                        .subAgents(subAgents)
                        .maxIterations(currentAgentWorkflow.getMaxIterations() != null
                                ? currentAgentWorkflow.getMaxIterations() : 3)
                        .build();

        dynamicContext.getAgentGroup().put(currentAgentWorkflow.getName(), reflexionAgent);

        log.info("反思迭代工作流装配完成: name={}, subAgents={}, maxIterations={}",
                currentAgentWorkflow.getName(), subAgentNames,
                currentAgentWorkflow.getMaxIterations() != null ? currentAgentWorkflow.getMaxIterations() : 3);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(
            ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return getBean("agentWorkflowNode");
    }
}

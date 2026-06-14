package cn.chyuan.ai.domain.agent.service.armory.node.workflow;

import cn.chyuan.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.chyuan.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.chyuan.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LoopAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service("loopAgentNode")
public class LoopAgentNode extends AbstractArmorySupport {

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - LoopAgentNode");

        AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow = dynamicContext.getCurrentAgentWorkflow();

        // 【Agentic Workflow】可选强门控：配置了 exitAgent + passPattern 时增强 Critic
        // 使 LoopAgent 支持条件提前退出，而非只能跑满 maxIterations
        String exitAgent = currentAgentWorkflow.getExitAgent();
        String passPattern = currentAgentWorkflow.getPassPattern();
        boolean gateEnabled = exitAgent != null && !exitAgent.isBlank()
                && passPattern != null && !passPattern.isBlank();
        if (gateEnabled) {
            boolean enhanced = dynamicContext.enhanceAgent(exitAgent,
                    ctx -> AgenticWorkflowEnhancer.attachExitLoopGate(ctx, passPattern, currentAgentWorkflow.getFailKeywords()));
            if (!enhanced) {
                log.warn("LoopAgent[{}] exitAgent[{}] 无 Builder 缓存，强门控未生效", currentAgentWorkflow.getName(), exitAgent);
            } else {
                log.info("LoopAgent[{}] 已为 Critic[{}] 挂强门控 passPattern={}", currentAgentWorkflow.getName(), exitAgent, passPattern);
            }
        }

        List<String> subAgentNames = currentAgentWorkflow.getSubAgents();
        List<BaseAgent> subAgents = dynamicContext.queryAgentList(subAgentNames);

        LoopAgent loopAgent =
                LoopAgent.builder()
                        .name(currentAgentWorkflow.getName())
                        .description(currentAgentWorkflow.getDescription())
                        .subAgents(subAgents)
                        .maxIterations(currentAgentWorkflow.getMaxIterations())
                        .build();

        dynamicContext.getAgentGroup().put(currentAgentWorkflow.getName(), loopAgent);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return getBean("agentWorkflowNode");
    }

}

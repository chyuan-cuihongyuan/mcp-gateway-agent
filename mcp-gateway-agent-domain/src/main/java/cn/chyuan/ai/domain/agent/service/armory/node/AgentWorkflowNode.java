package cn.chyuan.ai.domain.agent.service.armory.node;

import cn.chyuan.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.chyuan.ai.domain.agent.model.valobj.enums.AgentTypeEnum;
import cn.chyuan.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.chyuan.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.chyuan.ai.domain.agent.service.armory.node.workflow.LoopAgentNode;
import cn.chyuan.ai.domain.agent.service.armory.node.workflow.ParallelAgentNode;
import cn.chyuan.ai.domain.agent.service.armory.node.workflow.SequentialAgentNode;
import cn.chyuan.ai.domain.agent.service.armory.node.workflow.ReflectionAgentNode;
import cn.chyuan.ai.domain.agent.service.armory.node.workflow.ReflexionAgentNode;
import cn.chyuan.ai.domain.agent.service.armory.node.workflow.ReplanAgentNode;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;

@Slf4j
@Service
public class AgentWorkflowNode extends AbstractArmorySupport {

    @Resource
    private LoopAgentNode loopAgentNode;
    @Resource
    private ParallelAgentNode parallelAgentNode;
    @Resource
    private SequentialAgentNode sequentialAgentNode;
    @Resource
    private ReflectionAgentNode reflectionAgentNode;
    @Resource
    private ReflexionAgentNode reflexionAgentNode;
    @Resource
    private ReplanAgentNode replanAgentNode;
    @Resource
    private RunnerNode runnerNode;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - AgentWorkflowNode");

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        List<AiAgentConfigTableVO.Module.AgentWorkflow> agentWorkflows = aiAgentConfigTableVO.getModule().getAgentWorkflows();

        if (null == agentWorkflows || agentWorkflows.isEmpty() || dynamicContext.getCurrentStepIndex() >= agentWorkflows.size()) {
            // 设置结果�?
            dynamicContext.setCurrentAgentWorkflow(null);
            // 路由下节�?
            return router(requestParameter, dynamicContext);
        }

        dynamicContext.setCurrentAgentWorkflow(agentWorkflows.get(dynamicContext.getCurrentStepIndex()));

        // 步骤值增�?
        dynamicContext.addCurrentStepIndex();

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {

        AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow = dynamicContext.getCurrentAgentWorkflow();

        if (null == currentAgentWorkflow){
            return runnerNode;
        }

        String type = currentAgentWorkflow.getType();
        AgentTypeEnum agentTypeEnum = AgentTypeEnum.formType(type);

        if (null == agentTypeEnum){
            throw new RuntimeException("agentWorkflow type is error!");
        }

        String node = agentTypeEnum.getNode();

        return switch (node){
            case "loopAgentNode" -> loopAgentNode;
            case "parallelAgentNode" -> parallelAgentNode;
            case "sequentialAgentNode" -> sequentialAgentNode;
            // 【同步自 aggregation-support-agent】高级 Agentic Workflow 路由
            case "reflectionAgentNode" -> reflectionAgentNode;
            case "reflexionAgentNode" -> reflexionAgentNode;
            case "replanAgentNode" -> replanAgentNode;
            default -> runnerNode;
        };
    }

}

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

import java.util.ArrayList;
import java.util.List;

/**
 * 动态重规划工作流节点 — Phase 4: Dynamic Replan Agent 模式
 *
 * Replan = Plan → Execute → Evaluate → (Replan | Finish)
 * 动态调整计划流程：Planner（规划）→ Executor（执行）→ Evaluator（评估）→ Replan Loop（条件重规划）
 *
 * 【设计思路】
 * - 初始阶段：Planner 制定初始计划
 * - 执行阶段：Executor 执行计划
 * - 评估阶段：Evaluator 评估执行结果
 * - 重规划阶段：如果评估失败，Replan Loop 重新规划；否则结束
 *
 * 【关键机制】
 * - 条件重规划：使用 LoopAgent 构建 Replan Loop，最多迭代 maxIterations 次
 * - 退出条件：Evaluator 输出 PASSED 时，Replan Loop 中的 Replanner 调用 exitLoop 退出
 * - 状态传递：通过 outputKey 在各 Agent 间传递计划、执行结果、评估结论
 *
 * 【实现方式】
 * - 外层使用 SequentialAgent 构建四阶段流程
 * - Replan Loop 使用 LoopAgent 实现条件重规划
 * - 支持配置 maxIterations 控制最大重规划次数（通常 1-2 次）
 * - 配置示例见 super-agent.yml 中的 replan 工作流
 *
 * @author chyuan
 * @since 2025-06-13
 */
@Slf4j
@Service("replanAgentNode")
public class ReplanAgentNode extends AbstractArmorySupport {

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("【Phase 4】Ai Agent 装配操作 - ReplanAgentNode");

        AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow = dynamicContext.getCurrentAgentWorkflow();

        List<String> subAgentNames = currentAgentWorkflow.getSubAgents();
        if (subAgentNames == null || subAgentNames.isEmpty()) {
            log.warn("Replan 工作流需要至少 3 个子 Agent（Planner、Executor、Evaluator），当前配置为空");
        } else if (subAgentNames.size() < 3) {
            log.warn("Replan 工作流建议配置 4 个子 Agent（Planner、Executor、Evaluator、Replan Loop），当前: {} 个", subAgentNames.size());
        }

        // 构建 Replan 工作流：Planner → Executor → Evaluator → (Replan Loop)
        List<BaseAgent> sequentialAgents = new ArrayList<>();
        List<BaseAgent> allSubAgents = dynamicContext.queryAgentList(subAgentNames);

        if (allSubAgents.size() >= 3) {
            // 前 3 个 Agent：Planner、Executor、Evaluator
            sequentialAgents.add(allSubAgents.get(0));  // Planner
            sequentialAgents.add(allSubAgents.get(1));  // Executor
            sequentialAgents.add(allSubAgents.get(2));  // Evaluator

            // 如果有第 4 个 Agent，作为 Replan Loop（通常是一个 LoopAgent）
            if (allSubAgents.size() >= 4) {
                sequentialAgents.add(allSubAgents.get(3));  // Replan Loop
            }
        } else {
            // 容错：如果配置不足，使用所有可用的 Agent
            sequentialAgents.addAll(allSubAgents);
        }

        // 使用 SequentialAgent 构建重规划工作流
        SequentialAgent replanAgent =
                SequentialAgent.builder()
                        .name(currentAgentWorkflow.getName())
                        .description(currentAgentWorkflow.getDescription())
                        .subAgents(sequentialAgents)
                        .build();

        dynamicContext.getAgentGroup().put(currentAgentWorkflow.getName(), replanAgent);

        log.info("动态重规划工作流装配完成: name={}, subAgents={}, maxIterations={}",
                currentAgentWorkflow.getName(), subAgentNames,
                currentAgentWorkflow.getMaxIterations() != null ? currentAgentWorkflow.getMaxIterations() : 2);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(
            ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return getBean("agentWorkflowNode");
    }
}

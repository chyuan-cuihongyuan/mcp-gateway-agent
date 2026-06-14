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
 * 动态重规划工作流节点 — M1: 真正的 Plan → Execute → Evaluate → 条件 Replan
 *
 * <h3>工作流拓扑（真正的条件循环，非空壳）</h3>
 * <pre>
 * SequentialAgent(replan_workflow)
 * ├─ Planner                 outputKey: plan_result（初始规划）
 * └─ ReplanLoop (LoopAgent, maxIterations)
 *     ├─ Executor            读 {plan_result}，outputKey: execution_result
 *     ├─ Evaluator           读 {execution_result}，挂 ExitLoopTool + 强门控
 *     │                      PASSED → escalate 退出循环；NEEDS_REPLAN → 继续
 *     └─ Replanner           读 {plan_result}+{aiops_evaluation}，outputKey: plan_result（覆盖，供下一轮 Executor）
 * </pre>
 *
 * <h3>条件退出机制（双保险）</h3>
 * <ol>
 *   <li>ExitLoopTool：Evaluator 调用 exit_loop 工具 → LoopAgent 检测 escalate 退出</li>
 *   <li>强门控 afterModelCallback：代码解析 Evaluator 输出，命中 "PASSED" 则强制 setEscalate(true)</li>
 * </ol>
 *
 * <h3>子 agent 约定顺序</h3>
 * subAgents[0]=Planner, [1]=Executor, [2]=Evaluator, [3]=Replanner。
 * Evaluator 可通过 {@code exit-agent} 配置覆盖（默认取 [2]）。
 * 子 agent 不足 4 个时降级为纯顺序执行，保持向后兼容。
 *
 * @author chyuan
 * @since 2026-06-13
 */
@Slf4j
@Service("replanAgentNode")
public class ReplanAgentNode extends AbstractArmorySupport {

    /** Replan 工作流要求的最少子 agent 数：Planner、Executor、Evaluator、Replanner */
    private static final int MIN_SUB_AGENTS = 4;

    /** Evaluator 输出中表示"评估通过"的关键字（命中即触发退出） */
    private static final String PASS_KEYWORD = "PASSED";

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("【M1】Ai Agent 装配操作 - ReplanAgentNode（真正的动态重规划）");

        AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow = dynamicContext.getCurrentAgentWorkflow();
        List<String> subAgentNames = currentAgentWorkflow.getSubAgents();

        if (subAgentNames == null || subAgentNames.size() < MIN_SUB_AGENTS) {
            log.warn("Replan 工作流需要 {} 个子 agent（Planner、Executor、Evaluator、Replanner），当前: {}，降级为顺序执行",
                    MIN_SUB_AGENTS, subAgentNames == null ? 0 : subAgentNames.size());
            return buildFallbackSequential(requestParameter, currentAgentWorkflow, dynamicContext);
        }

        // 约定顺序：[0]Planner [1]Executor [2]Evaluator [3]Replanner
        String plannerName = subAgentNames.get(0);
        String executorName = subAgentNames.get(1);
        String evaluatorName = (currentAgentWorkflow.getExitAgent() != null && !currentAgentWorkflow.getExitAgent().isBlank())
                ? currentAgentWorkflow.getExitAgent() : subAgentNames.get(2);
        String replannerName = subAgentNames.get(3);

        // #5 先验证子 agent 完整性（在 enhance 之前）—— 避免 enhance 后才发现缺失，
        // 导致降级 SequentialAgent 含被增强的 Evaluator（挂 ExitLoopTool/门控），而 Sequential 不处理 escalate
        BaseAgent planner = dynamicContext.getAgentGroup().get(plannerName);
        BaseAgent executor = dynamicContext.getAgentGroup().get(executorName);
        BaseAgent evaluator = dynamicContext.getAgentGroup().get(evaluatorName);
        BaseAgent replanner = dynamicContext.getAgentGroup().get(replannerName);
        if (planner == null || executor == null || evaluator == null || replanner == null) {
            log.error("Replan 工作流子 agent 缺失，降级为顺序执行（enhance 前）: planner={}, executor={}, evaluator={}, replanner={}",
                    planner, executor, evaluator, replanner);
            return buildFallbackSequential(requestParameter, currentAgentWorkflow, dynamicContext);
        }

        // 确认完整后再增强 Evaluator：挂 ExitLoopTool + 强门控（PASSED→escalate 退出循环）
        // 优先读配置 passPattern，为空时 fallback 到字面关键字 "PASSED"
        String effectivePattern = (currentAgentWorkflow.getPassPattern() != null && !currentAgentWorkflow.getPassPattern().isBlank())
                ? currentAgentWorkflow.getPassPattern() : PASS_KEYWORD;
        boolean enhanced = dynamicContext.enhanceAgent(evaluatorName,
                ctx -> AgenticWorkflowEnhancer.attachExitLoopGate(ctx, effectivePattern, currentAgentWorkflow.getFailKeywords()));
        if (!enhanced) {
            log.warn("Evaluator[{}] 无 Builder 缓存，无法挂 ExitLoopTool，退化为普通顺序节点", evaluatorName);
        }

        // A2: 阶段级 OTel span 上报 —— Planner/Executor/Evaluator/Replanner 各一个 span
        dynamicContext.enhanceAgent(plannerName,
                ctx -> AgenticWorkflowEnhancer.attachSpanEmitter(ctx, "replan.planner", "PLANNER", null));
        dynamicContext.enhanceAgent(executorName,
                ctx -> AgenticWorkflowEnhancer.attachSpanEmitter(ctx, "replan.executor.cycle", "EXECUTOR", null));
        dynamicContext.enhanceAgent(evaluatorName,
                ctx -> AgenticWorkflowEnhancer.attachSpanEmitter(ctx, "replan.evaluator.cycle", "EVALUATOR", null));
        dynamicContext.enhanceAgent(replannerName,
                ctx -> AgenticWorkflowEnhancer.attachSpanEmitter(ctx, "replan.replanner.cycle", "REPLANNER", null));
        log.info("【A2 OTel】Replan[{}] 四个子 agent 阶段 span 已挂载", currentAgentWorkflow.getName());

        evaluator = dynamicContext.getAgentGroup().get(evaluatorName);  // enhance 覆盖后重新取

        int maxIterations = currentAgentWorkflow.getMaxIterations() != null ? currentAgentWorkflow.getMaxIterations() : 2;

        // 内层 Replan Loop：Executor → Evaluator → Replanner
        // 每轮执行后评估；Evaluator 判定 PASSED 时 escalate 退出；未达标则 Replanner 重规划，
        // 其 outputKey 覆盖 plan_result，下一轮 Executor 使用新计划。
        LoopAgent replanLoop = LoopAgent.builder()
                .name(currentAgentWorkflow.getName() + "_replanLoop")
                .description("动态重规划循环：执行→评估→重规划，评估通过则退出")
                .subAgents(executor, evaluator, replanner)
                .maxIterations(maxIterations)
                .build();

        // 外层：Planner（初始规划）→ ReplanLoop（执行 + 评估 + 条件重规划）
        SequentialAgent replanWorkflow = SequentialAgent.builder()
                .name(currentAgentWorkflow.getName())
                .description(currentAgentWorkflow.getDescription())
                .subAgents(planner, replanLoop)
                .build();

        dynamicContext.getAgentGroup().put(currentAgentWorkflow.getName(), replanWorkflow);

        log.info("动态重规划工作流装配完成: name={}, planner={}, executor={}, evaluator={}, replanner={}, maxIterations={}",
                currentAgentWorkflow.getName(), plannerName, executorName, evaluatorName, replannerName, maxIterations);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(
            ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return getBean("agentWorkflowNode");
    }

}

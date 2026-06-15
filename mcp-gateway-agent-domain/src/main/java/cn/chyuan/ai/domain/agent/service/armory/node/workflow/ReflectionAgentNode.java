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
 * 反思工作流节点 — Phase 2: Reflection Agent 模式（增强版）
 *
 * <h3>工作流拓扑</h3>
 * <pre>
 * LoopAgent(reflection, maxIterations=1)
 * ├─ Actor (subAgents[0])
 * │    outputKey: task_result
 * ├─ Critic (subAgents[1])
 * │    tools: [ExitLoopTool] + afterModelCallback 强门控
 * │    outputKey: quality_verdict
 * └─ Reflector (subAgents[2])
 *      outputKey: rag_reflection
 * </pre>
 *
 * <h3>与 Reflexion 的区别</h3>
 * <ul>
 *   <li>Reflection: maxIterations=1（单轮反思），适合质量要求不高但需要评估的场景</li>
 *   <li>Reflexion: maxIterations=3+（多轮迭代 + 跨迭代记忆），适合需要持续改进的高质量场景</li>
 * </ul>
 *
 * <h3>增强机制（2026-06-15 升级）</h3>
 * <ul>
 *   <li>使用 LoopAgent 替代 SequentialAgent，统一退出机制</li>
 *   <li>Critic 挂 ExitLoopTool + 强门控（PASSED→escalate 退出循环）</li>
 *   <li>A2 阶段级 OTel span 上报</li>
 *   <li>A6 Conditional 谓词短路（可选）</li>
 *   <li>子 agent 不足 3 个时降级为纯顺序执行</li>
 * </ul>
 *
 * @author chyuan
 * @since 2025-06-13
 */
@Slf4j
@Service("reflectionAgentNode")
public class ReflectionAgentNode extends AbstractArmorySupport {

    /** Reflection 工作流要求的最少子 agent 数：Actor、Critic、Reflector */
    private static final int MIN_SUB_AGENTS = 3;

    /** Critic 输出中表示"评估通过"的关键字（命中即触发退出） */
    private static final String PASS_KEYWORD = "PASSED";

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("【Phase 2】Ai Agent 装配操作 - ReflectionAgentNode（增强版）");

        AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow = dynamicContext.getCurrentAgentWorkflow();
        List<String> subAgentNames = currentAgentWorkflow.getSubAgents();

        if (subAgentNames == null || subAgentNames.size() < MIN_SUB_AGENTS) {
            log.warn("Reflection 工作流需要 {} 个子 agent（Actor、Critic、Reflector），当前: {}，降级为顺序执行",
                    MIN_SUB_AGENTS, subAgentNames == null ? 0 : subAgentNames.size());
            return buildFallbackSequential(requestParameter, currentAgentWorkflow, dynamicContext);
        }

        // 约定顺序：[0]Actor [1]Critic [2]Reflector
        String actorName = subAgentNames.get(0);
        String criticName = subAgentNames.get(1);
        String reflectorName = subAgentNames.get(2);

        // 先验证子 agent 完整性
        BaseAgent actor = dynamicContext.getAgentGroup().get(actorName);
        BaseAgent critic = dynamicContext.getAgentGroup().get(criticName);
        BaseAgent reflector = dynamicContext.getAgentGroup().get(reflectorName);
        if (actor == null || critic == null || reflector == null) {
            log.error("Reflection 工作流子 agent 缺失，降级为顺序执行: actor={}, critic={}, reflector={}",
                    actor, critic, reflector);
            return buildFallbackSequential(requestParameter, currentAgentWorkflow, dynamicContext);
        }

        // 增强 Critic：挂 ExitLoopTool + 强门控
        String effectivePattern = (currentAgentWorkflow.getPassPattern() != null && !currentAgentWorkflow.getPassPattern().isBlank())
                ? currentAgentWorkflow.getPassPattern() : PASS_KEYWORD;
        boolean criticEnhanced = dynamicContext.enhanceAgent(criticName,
                ctx -> AgenticWorkflowEnhancer.attachExitLoopGate(ctx, effectivePattern, currentAgentWorkflow.getFailKeywords()));
        if (!criticEnhanced) {
            log.warn("Critic[{}] 无 Builder 缓存，无法挂 ExitLoopTool，退化为普通顺序节点", criticName);
        }

        // A2: 阶段级 OTel span 上报
        dynamicContext.enhanceAgent(actorName,
                ctx -> AgenticWorkflowEnhancer.attachSpanEmitter(ctx,
                        "reflection.actor", "ACTOR", null));
        dynamicContext.enhanceAgent(criticName,
                ctx -> AgenticWorkflowEnhancer.attachSpanEmitter(ctx,
                        "reflection.critic", "CRITIC", null));
        dynamicContext.enhanceAgent(reflectorName,
                ctx -> AgenticWorkflowEnhancer.attachSpanEmitter(ctx,
                        "reflection.reflector", "REFLECTOR", null));
        log.info("【A2 OTel】Reflection[{}] 三个子 agent 阶段 span 已挂载", currentAgentWorkflow.getName());

        // A6: Conditional 谓词短路（可选）
        if (currentAgentWorkflow.getQueryPredicate() != null && !currentAgentWorkflow.getQueryPredicate().isBlank()) {
            String predicateRegex = currentAgentWorkflow.getQueryPredicate();
            boolean shortCircuitAttached = dynamicContext.enhanceAgent(actorName,
                    ctx -> AgenticWorkflowEnhancer.attachQueryPredicateShortCircuit(
                            ctx, predicateRegex, "您好，已收到您的简单咨询，已为您快速响应。"));
            if (shortCircuitAttached) {
                log.info("【A6 短路】Reflection[{}] Actor 已挂载谓词短路（regex={}）",
                        currentAgentWorkflow.getName(), predicateRegex);
            }
        }

        // enhance 覆盖后重新取实例
        actor = dynamicContext.getAgentGroup().get(actorName);
        critic = dynamicContext.getAgentGroup().get(criticName);
        reflector = dynamicContext.getAgentGroup().get(reflectorName);

        // 使用 LoopAgent 构建反思工作流（maxIterations=1 表示单轮反思）
        LoopAgent reflectionAgent = LoopAgent.builder()
                .name(currentAgentWorkflow.getName())
                .description(currentAgentWorkflow.getDescription())
                .subAgents(actor, critic, reflector)
                .maxIterations(1)
                .build();

        dynamicContext.getAgentGroup().put(currentAgentWorkflow.getName(), reflectionAgent);

        log.info("反思工作流装配完成: name={}, actor={}, critic={}, reflector={}, 增强状态: critic={}",
                currentAgentWorkflow.getName(), actorName, criticName, reflectorName, criticEnhanced);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(
            ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return getBean("agentWorkflowNode");
    }
}

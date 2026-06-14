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
 * Reflexion 反思迭代工作流节点 — M2: 真正的 Actor → Critic → Reflector + 跨迭代记忆
 *
 * <h3>工作流拓扑（真正的跨迭代记忆，非空壳）</h3>
 * <pre>
 * LoopAgent(rag_reflexion_loop, maxIterations=3)
 * ├─ Actor (ragAssistant)
 * │    beforeModelCallback: 读取 state["reflections:{name}"]，把累积反思注入 instruction
 * │    outputKey: rag_answer
 * ├─ Critic (rag_critic)
 * │    tools: [ExitLoopTool] + afterModelCallback 强门控（PASSED→escalate 退出循环）
 * │    outputKey: rag_evaluation
 * └─ Reflector (rag_reflector)
 *      afterAgentCallback: 读取 outputKey["rag_reflection"]，追加到 state["reflections:{name}"]
 *      outputKey: rag_reflection
 * </pre>
 *
 * <h3>跨迭代记忆机制</h3>
 * <ul>
 *   <li>Reflector 每轮产出反思后，afterAgentCallback 把反思追加到 session state 的累积列表</li>
 *   <li>Actor 下一轮执行前，beforeModelCallback 读取累积列表，拼接到 instruction（"历史反思教训"）</li>
 *   <li>Session state 在 LoopAgent 生命周期内共享（ConcurrentMap），无需外部存储</li>
 * </ul>
 *
 * <h3>子 agent 约定顺序</h3>
 * subAgents[0]=Actor, [1]=Critic, [2]=Reflector。
 * 子 agent 不足 3 个时降级为纯顺序执行，保持向后兼容。
 *
 * @author chyuan
 * @since 2026-06-13
 */
@Slf4j
@Service("reflexionAgentNode")
public class ReflexionAgentNode extends AbstractArmorySupport {

    /** Reflexion 工作流要求的最少子 agent 数：Actor、Critic、Reflector */
    private static final int MIN_SUB_AGENTS = 3;

    /** Critic 输出中表示"评估通过"的关键字（命中即触发退出） */
    private static final String PASS_KEYWORD = "PASSED";

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("【M2】Ai Agent 装配操作 - ReflexionAgentNode（真正的跨迭代记忆）");

        AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow = dynamicContext.getCurrentAgentWorkflow();
        List<String> subAgentNames = currentAgentWorkflow.getSubAgents();

        if (subAgentNames == null || subAgentNames.size() < MIN_SUB_AGENTS) {
            log.warn("Reflexion 工作流需要 {} 个子 agent（Actor、Critic、Reflector），当前: {}，降级为顺序执行",
                    MIN_SUB_AGENTS, subAgentNames == null ? 0 : subAgentNames.size());
            return buildFallbackSequential(requestParameter, currentAgentWorkflow, dynamicContext);
        }

        // 约定顺序：[0]Actor [1]Critic [2]Reflector
        String actorName = subAgentNames.get(0);
        String criticName = subAgentNames.get(1);
        String reflectorName = subAgentNames.get(2);

        // #5 先验证子 agent 完整性（在 enhance 之前）—— 避免 enhance 后才发现缺失，
        // 导致降级 SequentialAgent 含被增强的子 agent（挂 ExitLoopTool/记忆 callback）
        BaseAgent actor = dynamicContext.getAgentGroup().get(actorName);
        BaseAgent critic = dynamicContext.getAgentGroup().get(criticName);
        BaseAgent reflector = dynamicContext.getAgentGroup().get(reflectorName);
        if (actor == null || critic == null || reflector == null) {
            log.error("Reflexion 工作流子 agent 缺失，降级为顺序执行（enhance 前）: actor={}, critic={}, reflector={}",
                    actor, critic, reflector);
            return buildFallbackSequential(requestParameter, currentAgentWorkflow, dynamicContext);
        }

        // 从配置读取 Reflector 的 outputKey（用于记忆写入源）
        String reflectorOutputKey = resolveOutputKey(requestParameter, reflectorName, "rag_reflection");

        // 跨迭代反思记忆的 state key（默认 "reflections:{workflowName}"）
        String stateKey = (currentAgentWorkflow.getReflectionStateKey() != null && !currentAgentWorkflow.getReflectionStateKey().isBlank())
                ? currentAgentWorkflow.getReflectionStateKey()
                : "reflections:" + currentAgentWorkflow.getName();

        int maxIterations = currentAgentWorkflow.getMaxIterations() != null ? currentAgentWorkflow.getMaxIterations() : 3;

        // 确认完整后再增强三种子 agent
        // 优先读配置 passPattern，为空时 fallback 到字面关键字 "PASSED"
        String effectivePattern = (currentAgentWorkflow.getPassPattern() != null && !currentAgentWorkflow.getPassPattern().isBlank())
                ? currentAgentWorkflow.getPassPattern() : PASS_KEYWORD;
        boolean criticEnhanced = dynamicContext.enhanceAgent(criticName,
                ctx -> AgenticWorkflowEnhancer.attachExitLoopGate(ctx, effectivePattern, currentAgentWorkflow.getFailKeywords()));
        if (!criticEnhanced) {
            log.warn("Critic[{}] 无 Builder 缓存，无法挂 ExitLoopTool，退化为普通顺序节点", criticName);
        }
        boolean reflectorEnhanced = dynamicContext.enhanceAgent(reflectorName,
                ctx -> AgenticWorkflowEnhancer.attachReflectionWriter(ctx, reflectorOutputKey, stateKey));
        boolean actorEnhanced = dynamicContext.enhanceAgent(actorName,
                ctx -> AgenticWorkflowEnhancer.attachReflectionReader(ctx, stateKey));

        // A6: Conditional 谓词短路 —— 简单 query 直接跳过 LLM 调用，等效于不走 Reflexion
        if (currentAgentWorkflow.getQueryPredicate() != null && !currentAgentWorkflow.getQueryPredicate().isBlank()) {
            String predicateRegex = currentAgentWorkflow.getQueryPredicate();
            boolean shortCircuitAttached = dynamicContext.enhanceAgent(actorName,
                    ctx -> AgenticWorkflowEnhancer.attachQueryPredicateShortCircuit(
                            ctx, predicateRegex, "您好，已收到您的简单咨询，已为您快速响应。"));
            if (shortCircuitAttached) {
                log.info("【A6 短路】Reflexion[{}] Actor 已挂载谓词短路（regex={}）",
                        currentAgentWorkflow.getName(), predicateRegex);
            } else {
                log.warn("【A6 短路】Reflexion[{}] Actor[{}] 无 Builder 缓存，无法挂载谓词短路",
                        currentAgentWorkflow.getName(), actorName);
            }
        }

        // A2: 阶段级 OTel span 上报 —— 让 Reflexion 每轮迭代在 observability-server 可见
        // 三个子 agent 各挂一个 span，attributeProvider 把反思列表/分数历史写入 span 属性
        String otelWorkflowName = currentAgentWorkflow.getName();
        dynamicContext.enhanceAgent(actorName,
                ctx -> AgenticWorkflowEnhancer.attachSpanEmitter(ctx,
                        "reflexion.actor.iteration", "ACTOR",
                        AgenticWorkflowEnhancer.stateListSizeAttributes("reflections")));
        dynamicContext.enhanceAgent(criticName,
                ctx -> AgenticWorkflowEnhancer.attachSpanEmitter(ctx,
                        "reflexion.critic.iteration", "CRITIC",
                        AgenticWorkflowEnhancer.stateListSizeAttributes("reflections")));
        dynamicContext.enhanceAgent(reflectorName,
                ctx -> AgenticWorkflowEnhancer.attachSpanEmitter(ctx,
                        "reflexion.reflector.iteration", "REFLECTOR",
                        AgenticWorkflowEnhancer.stateListSizeAttributes("reflections")));
        log.info("【A2 OTel】Reflexion[{}] 三个子 agent 阶段 span 已挂载", otelWorkflowName);

        // A3: Reflexion 自动降级 —— 连续 N 轮无提升/低于阈值时强制退出循环
        // 挂在 Reflector 上（循环最后一个子 agent），读取 Critic 输出的分数进行降级判定
        boolean degradeEnabled = false;
        if (Boolean.TRUE.equals(currentAgentWorkflow.getDegradationEnabled())) {
            String criticOutputKey = resolveOutputKey(requestParameter, criticName, "rag_evaluation");
            String scoreRegex = currentAgentWorkflow.getScoreRegex();
            int patience = (currentAgentWorkflow.getDegradationPatience() != null && currentAgentWorkflow.getDegradationPatience() > 0)
                    ? currentAgentWorkflow.getDegradationPatience() : 2;
            int minIter = (currentAgentWorkflow.getDegradationMinIterations() != null && currentAgentWorkflow.getDegradationMinIterations() > 0)
                    ? currentAgentWorkflow.getDegradationMinIterations() : 2;
            double threshold = (currentAgentWorkflow.getGateThreshold() != null) ? currentAgentWorkflow.getGateThreshold() : 0.0;
            String historyKey = stateKey + ":scores";

            degradeEnabled = dynamicContext.enhanceAgent(reflectorName,
                    ctx -> AgenticWorkflowEnhancer.attachReflexionDegradeDetector(
                            ctx, criticOutputKey, scoreRegex, historyKey, patience, minIter, threshold));
            if (!degradeEnabled) {
                log.warn("Reflector[{}] 无 Builder 缓存，无法挂降级检测", reflectorName);
            } else {
                log.info("Reflexion 降级检测已挂载到 Reflector[{}]：criticOutputKey={}, patience={}, minIter={}, threshold={}",
                        reflectorName, criticOutputKey, patience, minIter, threshold);
            }
        }

        // enhance 覆盖后重新取实例
        actor = dynamicContext.getAgentGroup().get(actorName);
        critic = dynamicContext.getAgentGroup().get(criticName);
        reflector = dynamicContext.getAgentGroup().get(reflectorName);

        // 构建 Reflexion Loop：Actor → Critic → Reflector
        // 每轮 Actor 执行（带累积反思）→ Critic 评估（PASSED 退出）→ Reflector 产出反思（累积到 state）
        LoopAgent reflexionLoop = LoopAgent.builder()
                .name(currentAgentWorkflow.getName())
                .description(currentAgentWorkflow.getDescription())
                .subAgents(actor, critic, reflector)
                .maxIterations(maxIterations)
                .build();

        dynamicContext.getAgentGroup().put(currentAgentWorkflow.getName(), reflexionLoop);

        log.info("Reflexion 反思迭代工作流装配完成: name={}, actor={}, critic={}, reflector={}, stateKey={}, maxIterations={}, " +
                        "增强状态: actor={}, critic={}, reflector={}, degrade={}",
                currentAgentWorkflow.getName(), actorName, criticName, reflectorName, stateKey, maxIterations,
                actorEnhanced, criticEnhanced, reflectorEnhanced, degradeEnabled);

        return router(requestParameter, dynamicContext);
    }

    /** 从配置读取指定 agent 的 outputKey，找不到时返回默认值 */
    private String resolveOutputKey(ArmoryCommandEntity requestParameter, String agentName, String defaultOutputKey) {
        try {
            List<AiAgentConfigTableVO.Module.Agent> agents = requestParameter.getAiAgentConfigTableVO().getModule().getAgents();
            if (agents != null) {
                return agents.stream()
                        .filter(a -> agentName.equals(a.getName()))
                        .map(AiAgentConfigTableVO.Module.Agent::getOutputKey)
                        .findFirst()
                        .orElse(defaultOutputKey);
            }
        } catch (Exception e) {
            log.warn("读取 agent[{}] outputKey 失败，使用默认值: {}", agentName, defaultOutputKey);
        }
        return defaultOutputKey;
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(
            ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return getBean("agentWorkflowNode");
    }

}

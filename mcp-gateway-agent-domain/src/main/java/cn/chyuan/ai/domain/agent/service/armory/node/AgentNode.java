package cn.chyuan.ai.domain.agent.service.armory.node;

import cn.chyuan.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.chyuan.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.chyuan.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.chyuan.ai.domain.agent.service.armory.matter.patch.MySpringAI;
import cn.chyuan.ai.domain.agent.service.armory.matter.tools.ExitLoopTool;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.FunctionTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AgentNode extends AbstractArmorySupport {

    private static final String WILDCARD_TOOL = "*";

    @Resource
    private AgentWorkflowNode agentWorkflowNode;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - AgentNode");

        ChatModel defaultChatModel = dynamicContext.getChatModel();
        ChatModel noToolChatModel = dynamicContext.getNoToolChatModel();
        boolean globalHasTools = dynamicContext.isHasTools();
        List<ToolCallback> globalToolCallbacks = dynamicContext.getGlobalToolCallbacks();

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        List<AiAgentConfigTableVO.Module.Agent> agents = aiAgentConfigTableVO.getModule().getAgents();

        for (AiAgentConfigTableVO.Module.Agent agentConfig : agents) {
            // 【安全策略】子 agent 工具调用必须显式声明（配置即权限，未配置不允许调用）：
            // - tools == null：未配置 → 按安全策略不注入任何工具（禁止调用任何工具）
            // - tools == []：显式空列表 → 无工具变体（纯推理 agent）
            // - tools == ["*"]：通配符 → 注入工具池全部工具（显式声明使用全部）
            // - tools == ["t1","t2"]：白名单 → 仅注入工具池中匹配的工具（按名过滤）
            ChatModel effectiveChatModel;
            boolean effectiveHasTools;
            List<String> declaredTools = agentConfig.getTools();

            if (declaredTools == null) {
                // 未配置 tools → 安全策略：禁止任何工具调用
                effectiveChatModel = (noToolChatModel != null) ? noToolChatModel : defaultChatModel;
                effectiveHasTools = false;
                log.warn("Agent [{}] 未声明 tools 字段，按安全策略不注入任何工具（未配置不允许调用）", agentConfig.getName());
            } else if (declaredTools.isEmpty()) {
                // 显式空列表 → 无工具变体
                effectiveChatModel = (noToolChatModel != null) ? noToolChatModel : defaultChatModel;
                effectiveHasTools = false;
                log.info("Agent [{}] 声明 tools:[] → 使用无工具 ChatModel 变体", agentConfig.getName());
            } else if (declaredTools.contains(WILDCARD_TOOL)) {
                // 通配符 "*" → 注入工具池全部工具
                effectiveChatModel = defaultChatModel;
                effectiveHasTools = globalHasTools;
                log.info("Agent [{}] 声明 tools:[*] → 使用全部工具 ChatModel", agentConfig.getName());
            } else {
                // 白名单 → 仅注入声明的工具
                List<ToolCallback> filteredTools = filterToolCallbacks(globalToolCallbacks, declaredTools);
                if (filteredTools.isEmpty()) {
                    log.warn("Agent [{}] 声明工具 {} 在工具池中未匹配到任何工具，按安全策略不注入工具", agentConfig.getName(), declaredTools);
                    effectiveChatModel = (noToolChatModel != null) ? noToolChatModel : defaultChatModel;
                    effectiveHasTools = false;
                } else {
                    effectiveChatModel = buildFilteredChatModel(dynamicContext, filteredTools);
                    effectiveHasTools = true;
                    log.info("Agent [{}] 声明白名单 tools:{} → 注入 {} 个匹配工具: {}",
                            agentConfig.getName(), declaredTools, filteredTools.size(),
                            filteredTools.stream().map(t -> t.getToolDefinition().name()).collect(Collectors.toList()));
                }
            }

            LlmAgent.Builder llmAgentBuilder = LlmAgent.builder()
                    .name(agentConfig.getName())
                    .description(agentConfig.getDescription())
                    .model(new MySpringAI(effectiveChatModel, effectiveHasTools))
                    .instruction(agentConfig.getInstruction())
                    .outputKey(agentConfig.getOutputKey());

            // 设置 ReAct 循环最大步数
            // 无工具的纯对话智能体：强制 maxSteps=1，不允许 ADK 多轮调用 LLM
            // 有工具的智能体：使用配置的 maxSteps（需要多轮 Thought→Action→Observation）
            if (!effectiveHasTools) {
                log.info("Agent [{}] 无工具，强制 maxSteps=1，防止多轮自问自答", agentConfig.getName());
                llmAgentBuilder.maxSteps(1);
            } else if (agentConfig.getMaxSteps() != null && agentConfig.getMaxSteps() > 0) {
                llmAgentBuilder.maxSteps(agentConfig.getMaxSteps());
            }

            // 【Agentic Workflow】exitLoopEnabled → 注入 ExitLoopTool，使该 agent 可在 LoopAgent 中触发 escalate 提前退出
            // 适用于 Reflexion/Replan 工作流中的评估者 agent
            if (Boolean.TRUE.equals(agentConfig.getExitLoopEnabled())) {
                llmAgentBuilder.tools(java.util.List.of(FunctionTool.create(ExitLoopTool.class, "exitLoop")));
                log.info("Agent [{}] 启用 exitLoopEnabled，注入 ExitLoopTool", agentConfig.getName());
            }

            // 【Agentic Workflow】先缓存 Builder 到 agentBuilderGroup，供工作流节点（Replan/Reflexion）在 build 前 enhanceAgent
            // 注意：必须放在 build() 之前，否则 enhanceAgent 拿不到 Builder
            dynamicContext.getAgentBuilderGroup().put(agentConfig.getName(), llmAgentBuilder);

            LlmAgent llmAgent = llmAgentBuilder.build();
            dynamicContext.getAgentGroup().put(agentConfig.getName(), llmAgent);
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentWorkflowNode;
    }

    /**
     * 从全局工具池中按声明的工具名过滤出匹配的 ToolCallback（配置即权限，未声明的工具一律不可调用）
     */
    private List<ToolCallback> filterToolCallbacks(List<ToolCallback> pool, List<String> declared) {
        if (pool == null || pool.isEmpty() || declared == null || declared.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> declaredSet = new HashSet<>(declared);
        List<ToolCallback> filtered = new ArrayList<>();
        for (ToolCallback tc : pool) {
            if (declaredSet.contains(tc.getToolDefinition().name())) {
                filtered.add(tc);
            }
        }
        return filtered;
    }

    /**
     * 基于全局工具池中的指定子集构建工具受限的 ChatModel 变体（复用同一 openAiApi/model/maxTokens）
     */
    private ChatModel buildFilteredChatModel(DefaultArmoryFactory.DynamicContext dynamicContext, List<ToolCallback> filteredTools) {
        OpenAiApi openAiApi = dynamicContext.getOpenAiApi();
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(dynamicContext.getChatModelName())
                .toolCallbacks(filteredTools);
        if (dynamicContext.getChatModelMaxTokens() != null && dynamicContext.getChatModelMaxTokens() > 0) {
            optionsBuilder.maxTokens(dynamicContext.getChatModelMaxTokens());
        }
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(optionsBuilder.build())
                .build();
    }

}

package cn.chyuan.ai.domain.agent.service.armory.factory;

import cn.chyuan.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.chyuan.ai.domain.agent.service.armory.node.RootNode;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.SequentialAgent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 默认的装配工
 *
 * @author chyuan @chyuan
 * 2025/12/17 08:16
 */
@Service
public class DefaultArmoryFactory {

    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private RootNode rootNode;

    public StrategyHandler<ArmoryCommandEntity, DynamicContext, AiAgentRegisterVO> armoryStrategyHandler() {
        return rootNode;
    }

    public AiAgentRegisterVO getAiAgentRegisterVO(String agentId) {
        return applicationContext.getBean(agentId, AiAgentRegisterVO.class);
    }

    /**
     * 定义一个上下文对象，用于各个节点串联的时候，写入数据和使用数
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DynamicContext {

        /**
         * LLM API
         */
        private OpenAiApi openAiApi;

        /**
         * LLM ChatModel
         */
        private ChatModel chatModel;

        /**
         * 无工具 ChatModel 变体（tools 声明为空/未配置时使用）
         */
        private ChatModel noToolChatModel;

        /**
         * 是否注册了工具
         */
        private boolean hasTools;

        /**
         * 全局工具池：ChatModelNode 装配出的全部 ToolCallback（来自 tool-mcp-list + tool-skills-list）。
         * 供 AgentNode 按子 agent 的 tools 声明做白名单过滤，构建工具受限的 ChatModel 变体。
         */
        private List<ToolCallback> globalToolCallbacks;

        /**
         * ChatModel 模型名（用于按白名单构建受限 ChatModel 变体时复用同一模型）
         */
        private String chatModelName;

        /**
         * ChatModel 最大输出 token 数（用于按白名单构建受限 ChatModel 变体时复用同一限制）
         */
        private Integer chatModelMaxTokens;

        /**
         * 智能体配置组
         */
        @Builder.Default
        private Map<String, BaseAgent> agentGroup = new HashMap<>();

        @Builder.Default
        private AtomicInteger currentStepIndex = new AtomicInteger(0);

        private AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow;

        /**
         * Agent Builder 缓存：key = agentName，value = 对应 LlmAgent.Builder。
         * <p>由 AgentNode 装配时放入；工作流节点（如 Replan/Reflexion）通过 {@link #enhanceAgent}
         * 在 build 前对 Builder 做条件退出/记忆读写增强。
         */
        @Builder.Default
        private Map<String, LlmAgent.Builder> agentBuilderGroup = new HashMap<>();

        /**
         * AgentEnhancementContext 缓存：按 agentName 累积回调（避免 *CallbackSync 单元素 setter 覆盖）。
         * 同一 agent 多次 enhance 时复用同一 context，累积的回调由 flush() 一次性注入 Builder。
         */
        @Builder.Default
        private Map<String, AgentEnhancementContext> agentEnhancementContextGroup = new HashMap<>();

        @Builder.Default
        private Map<String, Object> dataObjects = new HashMap<>();

        public <T> void setValue(String key, T value) {
            dataObjects.put(key, value);
        }

        public <T> T getValue(String key) {
            return (T) dataObjects.get(key);
        }

        public List<BaseAgent> queryAgentList(List<String> agentNames) {
            if (agentNames == null || agentNames.isEmpty() || agentGroup == null) {
                return Collections.emptyList();
            }

            List<BaseAgent> agents = new ArrayList<>();
            for (String name : agentNames) {
                BaseAgent agent = agentGroup.get(name);
                if (agent != null) {
                    agents.add(agent);
                }
            }

            return agents;
        }

        public void addCurrentStepIndex() {
            currentStepIndex.incrementAndGet();
        }

        public int getCurrentStepIndex() {
            return currentStepIndex.get();
        }

        /**
         * 对指定 agent 的 Builder 执行增强（条件退出 / 跨迭代记忆），增强后重新 build 并覆盖 agentGroup。
         * <p>要求 AgentNode 装配时已把 Builder 放入 {@link #agentBuilderGroup}。
         *
         * @param agentName 待增强 agent 的名称
         * @param enhancer  增强回调（通过 {@link AgentEnhancementContext} 追加工具/回调，禁止改核心属性）
         * @return true 增强（重新 build）成功；false 表示 Builder 未缓存（agent 未由 AgentNode 装配或为非 LlmAgent）
         */
        public boolean enhanceAgent(String agentName, java.util.function.Consumer<AgentEnhancementContext> enhancer) {
            LlmAgent.Builder builder = agentBuilderGroup.get(agentName);
            if (builder == null) {
                return false;
            }
            AgentEnhancementContext ctx = agentEnhancementContextGroup
                    .computeIfAbsent(agentName, k -> new AgentEnhancementContext(builder));
            enhancer.accept(ctx);
            ctx.flush();
            agentGroup.put(agentName, builder.build());
            return true;
        }

    }

}

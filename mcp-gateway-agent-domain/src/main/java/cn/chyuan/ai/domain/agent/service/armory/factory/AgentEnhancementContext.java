package cn.chyuan.ai.domain.agent.service.armory.factory;

import com.google.adk.agents.Callbacks;
import com.google.adk.agents.LlmAgent;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 增强上下文 —— 受限的 LlmAgent.Builder 包装。
 * <p>
 * 只允许追加工具与回调（addTool / *CallbackSync），<b>禁止</b>修改 name/model/instruction 等核心属性，
 * 防止 {@code enhanceAgent} 的调用方误改 agent 身份导致装配错乱（#10）。
 * <p>
 * 放在 factory 包与 {@link DefaultArmoryFactory.DynamicContext} 同层，避免 factory ↔ workflow 循环依赖。
 * <p>
 * <b>回调累积语义（ADK 1.4.0 兼容）</b>：ADK 的 {@code *CallbackSync} 单元素方法是 setter（覆盖）而非 adder。
 * 同一 agent 若多次 enhance（如 Reflector 依次挂 reflectionWriter → A2 span → A3 降级），
 * 后者会覆盖前者导致跨迭代记忆失效。因此本类把回调累积到内部 List，由 {@link #flush()} 通过
 * {@code *Callback(List)} 版本一次性注入，确保多个回调共存。
 *
 * @author chyuan
 * @since 2026-06-13
 */
public class AgentEnhancementContext {

    private final LlmAgent.Builder builder;

    private final List<Callbacks.BeforeModelCallbackSync> beforeModelCallbacks = new ArrayList<>();
    private final List<Callbacks.AfterModelCallbackSync> afterModelCallbacks = new ArrayList<>();
    private final List<Callbacks.BeforeAgentCallbackSync> beforeAgentCallbacks = new ArrayList<>();
    private final List<Callbacks.AfterAgentCallbackSync> afterAgentCallbacks = new ArrayList<>();

    public AgentEnhancementContext(LlmAgent.Builder builder) {
        this.builder = builder;
    }

    /**
     * 追加工具（保留现有 agent 级工具，避免 setter 覆盖丢失）。
     */
    public AgentEnhancementContext addTool(Object tool) {
        List<Object> tools = new ArrayList<>(builder.build().toolsUnion());
        tools.add(tool);
        builder.tools(tools);
        return this;
    }

    public AgentEnhancementContext afterModelCallbackSync(Callbacks.AfterModelCallbackSync callback) {
        afterModelCallbacks.add(callback);
        return this;
    }

    public AgentEnhancementContext beforeModelCallbackSync(Callbacks.BeforeModelCallbackSync callback) {
        beforeModelCallbacks.add(callback);
        return this;
    }

    public AgentEnhancementContext afterAgentCallbackSync(Callbacks.AfterAgentCallbackSync callback) {
        afterAgentCallbacks.add(callback);
        return this;
    }

    /**
     * 获取当前 agent 的名字（只读，用于 span/日志等场景标识 agent 身份）。
     */
    public String getAgentName() {
        return builder.build().name();
    }

    /**
     * 把累积的回调一次性注入 Builder（通过 List 版本 setter，避免 *CallbackSync 单元素覆盖）。
     * <p>
     * 由 {@code enhanceAgent} 在每次增强后调用；累积 List 幂等，重复 flush 安全。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void flush() {
        if (!beforeModelCallbacks.isEmpty()) {
            builder.beforeModelCallback(new ArrayList<>(beforeModelCallbacks));
        }
        if (!afterModelCallbacks.isEmpty()) {
            builder.afterModelCallback(new ArrayList<>(afterModelCallbacks));
        }
        if (!beforeAgentCallbacks.isEmpty()) {
            builder.beforeAgentCallback(new ArrayList(beforeAgentCallbacks));
        }
        if (!afterAgentCallbacks.isEmpty()) {
            builder.afterAgentCallback(new ArrayList(afterAgentCallbacks));
        }
    }
}

package cn.chyuan.ai.domain.agent.service.armory.matter.tools;

import com.google.adk.tools.Annotations;
import com.google.adk.tools.ToolContext;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * ExitLoop 工具 — 用于 LoopAgent 中 Reflexion 循环的语义级提前退出
 * <p>
 * 当 Critic 评估结果为 SUFFICIENT 时，ExitOrReplan Agent 调用此工具触发 LoopAgent 退出，
 * 无需等待 maxIterations 硬限制。通过 Google ADK 的 toolContext.actions().setEscalate(true) 实现。
 * <p>
 * 使用方式：在 YAML 智能体配置中设置 exit-loop-enabled: true，AgentNode 会自动注入此工具。
 *
 * @author chyuan @chyuan
 * @since 2026/06/13
 */
@Slf4j
public class ExitLoopTool {

    /**
     * 退出循环工具方法
     * <p>
     * 调用此方法后，LoopAgent 会在当前迭代结束后退出循环，不再继续下一轮。
     * 适用于 Reflexion 循环中，当 Critic 判定结果质量为 SUFFICIENT 时提前退出。
     *
     * @param toolContext Google ADK 工具上下文，用于触发 escalate 退出信号
     * @return 空结果 Map
     */
    @Annotations.Schema(
            description = "当质量评估结果为 SUFFICIENT（充足）时，调用此工具退出 Reflexion 循环，" +
                    "不再继续迭代。只在评估通过时调用，评估不通过时不要调用。"
    )
    public static Map<String, Object> exitLoop(
            @Annotations.Schema(name = "toolContext") ToolContext toolContext) {
        // ADK 0.5.0 经 Spring AI ToolConverter 转换静态工具时，ToolContext 可能注入为 null。
        // 缺失上下文时无法安全触发 escalate 退出信号：记录告警并返回，
        // 避免反射调用抛 InvocationTargetException(NPE) 中断整个工作流（循环将按 maxIterations 兜底退出）。
        if (toolContext == null) {
            log.warn("[ExitLoop] ToolContext 为空，无法触发 escalate 退出信号（ADK ToolContext 注入缺失），"
                    + "循环将按 maxIterations 兜底退出");
            return Map.of();
        }

        String agentName = toolContext.agentName();
        log.info("[ExitLoop] Agent [{}] 触发退出循环 — 质量评估已通过", agentName);

        // 设置 escalate 标志，触发 LoopAgent 退出循环
        toolContext.actions().setEscalate(true);

        return Map.of();
    }

}

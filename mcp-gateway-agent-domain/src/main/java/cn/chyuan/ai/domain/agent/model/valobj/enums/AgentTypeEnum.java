package cn.chyuan.ai.domain.agent.model.valobj.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public enum AgentTypeEnum {

    Loop("循环执行", "loop", "loopAgentNode"),
    Parallel("并行执行", "parallel", "parallelAgentNode"),
    Sequential("串行执行", "sequential", "sequentialAgentNode"),

    // 【Phase 2-4 新增】高级 Agentic Workflow 模式
    Reflection("反思工作流", "reflection", "reflectionAgentNode"),
    Reflexion("反思迭代工作流", "reflexion", "reflexionAgentNode"),
    Replan("动态重规划工作流", "replan", "replanAgentNode"),

    // 【P0 新增】Human-in-the-Loop 人工审批机制
    HITL("人工审批工作流", "hitl", "humanInTheLoopAgentNode"),

    ;

    private String name;
    private String type;
    private String node;

    public static AgentTypeEnum formType(String type) {
        if (type == null) {
            return null;
        }

        for (AgentTypeEnum value : values()) {
            if (value.getType().equalsIgnoreCase(type)) {
                return value;
            }
        }

        return null;
    }

}

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

    // 【同步自 aggregation-support-agent】高级 Agentic Workflow 模式
    Reflection("反思工作流", "reflection", "reflectionAgentNode"),
    Reflexion("反思迭代工作流", "reflexion", "reflexionAgentNode"),
    Replan("动态重规划工作流", "replan", "replanAgentNode"),

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

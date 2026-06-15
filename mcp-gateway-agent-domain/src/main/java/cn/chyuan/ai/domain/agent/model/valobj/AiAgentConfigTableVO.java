package cn.chyuan.ai.domain.agent.model.valobj;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Ai Agent 智能体配置表值对象
 *
 * @author chyuan
 *         2025/11/29 10:54
 */
@Data
public class AiAgentConfigTableVO {

    /**
     * 应用名称
     */
    private String appName;

    /**
     * 智能体配置
     */
    private Agent agent;

    /**
     * 智能体模块
     */
    private Module module;

    @Data
    public static class Agent {

        /**
         * 智能体ID
         */
        private String agentId;

        /**
         * 智能体名称
         */
        private String agentName;

        /**
         * 智能体描述
         */
        private String agentDesc;

    }

    @Data
    public static class Module {

        private AiApi aiApi;

        private ChatModel chatModel;

        private List<Agent> agents;

        private List<AgentWorkflow> agentWorkflows;

        private Runner runner;

        @Data
        public static class AiApi {
            private String provider = "openai";
            private String baseUrl;
            private String apiKey;
            private String completionsPath = "/v1/chat/completions";
            private String embeddingsPath = "/v1/embeddings";
        }

        @Data
        public static class ChatModel {

            private String model;

            /**
             * 最大输出 token 数（用于按白名单构建受限 ChatModel 变体时复用同一限制）
             */
            private Integer maxTokens;

            private List<ToolMcp> toolMcpList;

            private List<ToolSkills> toolSkillsList;

            @Data
            public static class ToolMcp {

                private SSEServerParameters sse;

                private StdioServerParameters stdio;

                private LocalParameters local;

                /**
                 * M3: StreamableHttp 传输参数 —— 支持 MCP 协议 2025-03-26 版本的 StreamableHttp 传输。
                 * <p>
                 * 与 SSE 的区别：SSE 使用 GET 建立 long-lived 事件流；StreamableHttp 使用 POST 到单一端点（如 /mcp），
                 * 服务端可选择以 JSON 或 SSE-stream 响应，是 MCP 推荐的新传输方式。
                 */
                private StreamableHttpServerParameters streamableHttp;

                @Data
                public static class SSEServerParameters {
                    private String name;
                    private String baseUri;
                    private String sseEndpoint;
                    private String apiKey;
                    private Integer requestTimeout = 3000;

                }

                @Data
                public static class StdioServerParameters {
                    private String name;
                    private Integer requestTimeout = 3000;
                    private ServerParameters serverParameters;

                    @Data
                    public static class ServerParameters {
                        private String command;
                        private List<String> args;
                        private Map<String, String> env;

                    }
                }

                @Data
                public static class LocalParameters {
                    private String name;
                }

                /**
                 * StreamableHttp 传输参数（MCP 2025-03-26 规范）。
                 */
                @Data
                public static class StreamableHttpServerParameters {
                    private String name;
                    /** 服务端基础地址，如 https://api.example.com */
                    private String baseUri;
                    /**
                     * MCP 端点子路径（StreamableHttp 服务端通常为 /mcp）。
                     * 为空时默认 "/mcp"。支持子路径前缀，如 /api/v1/mcp。
                     */
                    private String mcpEndpoint = "/mcp";
                    /** 可选 Bearer Token，通过 Authorization 请求头传递 */
                    private String apiKey;
                    private Integer requestTimeout = 3000;
                }

            }

            @Data
            public static class ToolSkills {

                /**
                 * 类型；directory（用户配置的，映射进来的）、resource（放到工程下的）
                 */
                private String type = "directory";

                /**
                 * 路径；
                 */
                private String path;

            }

        }

        @Data
        public static class Agent {
            private String name;
            private String instruction;
            private String description;
            private String outputKey;

            /**
             * 工具白名单（配置即权限，未配置不允许调用）：
             * - null：未配置 → 按安全策略不注入任何工具（禁止调用）
             * - []：显式空列表 → 无工具变体（纯推理 agent）
             * - ["*"]：通配符 → 注入工具池全部工具
             * - ["t1","t2"]：白名单 → 仅注入工具池中匹配的工具
             */
            private List<String> tools;

            /**
             * 是否启用 ReAct 推理模式（带工具的逐步推理）
             */
            private Boolean reactMode;

            /**
             * 最大推理步数
             */
            private Integer maxSteps;

            /**
             * 是否注入退出循环工具
             */
            private Boolean exitLoopEnabled;

        }

        @Data
        public static class AgentWorkflow {
            /**
             * 类型；loop、parallel、sequential、reflection、reflexion、replan
             */
            private String type;
            private String name;
            private List<String> subAgents;
            private String description;
            private Integer maxIterations = 3;
            /**
             * Replan 工作流：评估通过后接管的"退出 Agent"名称（通常是 ExitOrReplan Agent）。
             * 为空时 Replan 节点会尝试用 subAgents 中的最后一个作为退出 Agent。
             */
            private String exitAgent;
            /**
             * Replan/Reflexion 工作流：评估者输出中表示"通过"的正则模式，命中即触发 escalate 退出循环。
             * 例：{@code "verdict"\s*:\s*"SUFFICIENT"}。
             * 为空时仅挂 ExitLoopTool，不启用强门控。
             */
            private String passPattern;
            /**
             * Replan/Reflexion 工作流：评估者输出中表示"需要返工"的失败关键字（逗号分隔），
             * 命中任一则不触发退出。默认 "INSUFFICIENT,NEEDS_REPLAN,NEEDS_IMPROVEMENT"。
             */
            private String failKeywords;
            /**
             * Reflexion 工作流：跨迭代反思累积的 session state key，如 "reflections:rag"。
             * 为空时 Reflexion 节点不启用记忆读写，退化为普通 LoopAgent。
             */
            private String reflectionStateKey;
            /**
             * Reflexion 工作流：质量评估阈值（与 Critic 输出分数比较）。
             * 用于 A3 自动降级机制：分数持续低于此阈值时强制退出 Reflexion 循环。
             * 默认 7.0；≤0 表示仅用"无提升"判定，不启用阈值降级。
             */
            private Double gateThreshold = 7.0;
            /**
             * 是否启用 Reflexion 自动降级 —— 连续多轮反思无提升（或分数持续低于 gateThreshold）时
             * 自动退出循环，避免无效反思消耗 token。
             * <p>
             * 默认 false（向后兼容）。
             */
            private Boolean degradationEnabled = false;
            /**
             * Reflexion 降级容忍轮数 —— 连续 N 轮无提升即触发降级退出。
             * <p>
             * 默认 2。
             */
            private Integer degradationPatience = 2;
            /**
             * Reflexion 降级最小迭代数 —— 至少迭代 N 次后才考虑降级。
             * <p>
             * 默认 2。
             */
            private Integer degradationMinIterations = 2;
            /**
             * Reflexion 降级评分提取正则 —— 从 Critic/Evaluator 输出提取分数。
             * <p>
             * 默认 {@code "score"\s*:\s*([\d.]+)}。
             */
            private String scoreRegex = "\"score\"\\s*:\\s*([\\d.]+)";
            /**
             * A6 Conditional 谓词 —— 简单 query 短路正则。
             * <p>
             * 配置后，Reflexion Actor 在 beforeModelCallback 检查 user query：
             * 匹配此正则 → 视为"简单 query"，返回简短响应跳过 LLM 调用，
             * Critic 第一轮 PASSED，Reflexion loop 立即退出（等效于不走 Reflexion）。
             * 为空表示不启用短路。
             */
            private String queryPredicate;

            // ============================== P0: Human-in-the-Loop 配置 ==============================

            /**
             * HITL 审批渠道 —— 人工审批的通知方式。
             * <p>
             * 可选值：
             * <ul>
             *   <li>"web" — Web UI 审批（默认）</li>
             *   <li>"feishu" — 飞书消息审批</li>
             *   <li>"email" — 邮件审批</li>
             *   <li>"dingtalk" — 钉钉消息审批</li>
             * </ul>
             * 为空时默认 "web"。
             */
            private String approvalChannel = "web";

            /**
             * HITL 审批超时时间（秒）—— 超过此时间未审批则自动拒绝或跳过。
             * <p>
             * 默认 300 秒（5 分钟）。
             */
            private Integer approvalTimeoutSeconds = 300;

            /**
             * HITL 风险等级 —— 决定是否需要人工审批。
             * <p>
             * 可选值：
             * <ul>
             *   <li>"high" — 高风险操作，必须人工审批</li>
             *   <li>"medium" — 中风险操作，建议人工审批（可配置自动通过）</li>
             *   <li>"low" — 低风险操作，自动通过</li>
             * </ul>
             * 为空时默认 "medium"。
             */
            private String riskLevel = "medium";

        }

        @Data
        public static class Runner {
            private String agentName;
            private List<String> pluginNameList;
        }
    }

}

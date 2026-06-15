package cn.chyuan.ai.domain.agent.service.armory.node.workflow;

import cn.chyuan.ai.domain.agent.service.armory.factory.AgentEnhancementContext;
import cn.chyuan.ai.domain.agent.service.armory.matter.tools.ExitLoopTool;
import com.google.adk.agents.CallbackContext;
import com.google.adk.events.EventActions;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Agentic Workflow 增强器 —— 为工作流中的子 agent 注入"条件退出"与"跨迭代记忆"能力。
 *
 * <p>M1（动态 Replan）与 M2（Reflexion 反思）共用本类。
 *
 * <h3>设计依据（反编译 ADK 0.5.0 字节码确认，非文档推测）</h3>
 * <ul>
 *   <li>{@code ExitLoopTool.exitLoop(ToolContext)} 实现：
 *       {@code toolContext.setActions(actions.toBuilder().escalate(true).build())}，
 *       LoopAgent.runAsyncImpl 的 {@code takeUntil(hasEscalateAction)} 检测到后提前退出循环。</li>
 *   <li>{@code CallbackContext.eventActions()} 返回可变 {@link EventActions}，
 *       其 {@code setEscalate(boolean)} 可在 callback 中代码强制触发退出。</li>
 *   <li>{@code CallbackContext.state()}（Session state）是 {@code ConcurrentMap}，
 *       整个 LoopAgent 生命周期共享，Reflexion 借此累积跨迭代反思。</li>
 * </ul>
 *
 * @author chyuan
 * @since 2026-06-13
 */
@Slf4j
public final class AgenticWorkflowEnhancer {

    /** 评估者输出中表示"需要返工"的关键字（命中则不退出循环） */
    private static final String NEEDS_REPLAN_KEYWORD = "NEEDS_REPLAN";
    private static final String NEEDS_IMPROVE_KEYWORD = "NEEDS_IMPROVEMENT";

    private AgenticWorkflowEnhancer() {
    }

    /**
     * 给评估者（Evaluator/Critic）挂"条件退出"能力 —— 双保险退出机制（精确版本，支持正则匹配）。
     *
     * <ol>
     *   <li>挂 {@link ExitLoopTool}：LLM 在 instruction 引导下调用 exit_loop 工具触发 escalate（ADK 官方机制）</li>
     *   <li>afterModelCallback 强门控：解析响应文本，命中 passPattern 正则且不含失败关键字时，
     *       代码强制 {@code setEscalate(true)}，不依赖 LLM 自觉调用</li>
     * </ol>
     *
     * @param ctx            评估者 agent 的增强上下文
     * @param passPattern    视为"评估通过"的正则模式，命中即强制退出循环。
     *                       例：{@code "verdict"\s*:\s*"SUFFICIENT"} 精确匹配 JSON 字段值，
     *                       避免 {@code INSUFFICIENT} 子串误命中 {@code SUFFICIENT}。
     *                       为 null 时不启用强门控，仅挂 ExitLoopTool。
     * @param failKeywordsCsv 逗号分隔的失败关键字（命中则不触发退出），默认 "INSUFFICIENT,NEEDS_REPLAN,NEEDS_IMPROVEMENT"
     */
    public static void attachExitLoopGate(AgentEnhancementContext ctx, String passPattern, String failKeywordsCsv) {
        // 1. 追加 ExitLoopTool（ctx.addTool 内部读现有 toolsUnion 合并，避免 setter 覆盖丢失已有工具 #7）
        //    使用项目自定义 ExitLoopTool（matter.tools.ExitLoopTool），与 AgentNode 的 exitLoopEnabled 机制统一
        ctx.addTool(FunctionTool.create(ExitLoopTool.class, "exitLoop"));

        // 2. 解析失败关键字列表
        final java.util.List<String> failKeywords = java.util.Arrays.stream(
                (failKeywordsCsv == null ? "INSUFFICIENT,NEEDS_REPLAN,NEEDS_IMPROVEMENT" : failKeywordsCsv).split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(String::toUpperCase).toList();

        // 3. 预编译正则（延迟到 lambda 内部，避免 effectively final 问题）
        final String finalPattern = passPattern;

        // 4. afterModelCallback 强门控 —— 精确正则匹配 + 失败关键字排除
        ctx.afterModelCallbackSync((callbackContext, llmResponse) -> {
            String text = extractText(llmResponse);
            if (text == null) {
                return Optional.empty();
            }

            String upper = text.toUpperCase();
            boolean needsRework = failKeywords.stream().anyMatch(upper::contains);
            boolean passed = false;
            if (finalPattern != null && !finalPattern.isBlank()) {
                try {
                    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(finalPattern, java.util.regex.Pattern.CASE_INSENSITIVE);
                    passed = pattern.matcher(text).find();
                } catch (Exception e) {
                    log.error("【强门控】正则匹配失败: {}", finalPattern, e);
                }
            }

            if (passed && !needsRework) {
                EventActions actions = callbackContext.eventActions();
                if (actions != null) {
                    actions.setEscalate(true);
                    log.info("【强门控】评估通过[match={}, text={}]", finalPattern, text.substring(0, Math.min(100, text.length())));
                } else {
                    // eventActions 为 null 通常意味着 agent 不在 LoopAgent 上下文中（escalate 只对 LoopAgent 生效）
                    log.warn("【强门控失效】eventActions 为 null，agent 可能不在 LoopAgent 中，强门控无法触发，依赖 ExitLoopTool/LLM 兜底");
                }
            }
            return Optional.empty();
        });

        log.info("已为评估者追加 ExitLoopTool + 强门控(passPattern={}, failKeywords={})", passPattern, failKeywordsCsv);
    }

    /**
     * 给评估者（Evaluator/Critic）挂"条件退出"能力 —— 双保险退出机制（向后兼容版本）。
     *
     * <p>委托到 3 参数版本，将 passKeyword 包装为字面匹配正则（通过 {@link java.util.regex.Pattern#quote}）。
     *
     * @param ctx        评估者 agent 的 Builder
     * @param passKeyword 视为"评估通过"的关键字（字面匹配），命中即强制退出循环
     */
    public static void attachExitLoopGate(AgentEnhancementContext ctx, String passKeyword) {
        String pattern = (passKeyword != null && !passKeyword.isBlank())
                ? java.util.regex.Pattern.quote(passKeyword)
                : null;
        attachExitLoopGate(ctx, pattern, null);
    }

    /**
     * 给反思改进者（Reflector）挂"写入记忆"能力 —— 每轮结束后把本轮反思追加到 session state。
     *
     * <p>读取 Reflector 的 outputKey 对应 state（ADK 在 agent 完成时已写入 outputKey），
     * 累积到 reflections 列表，供下一轮 Actor 读取。
     *
     * @param builder   Reflector 的 Builder
     * @param outputKey Reflector 的 outputKey（其输出已写入 state[outputKey]）
     * @param stateKey  反思累积的 state key（如 "reflections:rag"）
     */
    public static void attachReflectionWriter(AgentEnhancementContext ctx, String outputKey, String stateKey) {
        ctx.afterAgentCallbackSync(callbackContext -> {
            Object output = callbackContext.state().get(outputKey);
            if (!(output instanceof String reflection) || reflection.isBlank()) {
                return Optional.empty();
            }
            List<String> reflections = getOrCreateReflectionList(callbackContext, stateKey);
            reflections.add(reflection);
            callbackContext.state().put(stateKey, reflections);
            log.info("【Reflexion】追加第 {} 轮反思到 state[{}]（来源 outputKey={}）", reflections.size(), stateKey, outputKey);
            return Optional.empty();
        });
    }

    /**
     * 给执行者（Actor）挂"读取记忆"能力 —— 每轮模型调用前把累积反思注入 instruction。
     *
     * <p>通过 beforeModelCallback 修改 LlmRequest 的指令，把历史反思作为"避免重复错误"的上下文拼入。
     *
     * @param builder  Actor 的 Builder
     * @param stateKey 反思累积的 state key
     */
    public static void attachReflectionReader(AgentEnhancementContext ctx, String stateKey) {
        ctx.beforeModelCallbackSync((callbackContext, llmRequestBuilder) -> {
            Object val = callbackContext.state().get(stateKey);
            if (val instanceof List<?> list && !list.isEmpty()) {
                StringBuilder sb = new StringBuilder("\n\n## 历史反思教训（避免重复同样的错误，请据此改进本轮执行）\n");
                for (int i = 0; i < list.size(); i++) {
                    sb.append("第").append(i + 1).append("轮反思：").append(list.get(i)).append("\n");
                }
                // 把反思拼接到指令末尾（appendInstructions 接收 List<String>，反编译 ADK 0.5.0 确认）
                llmRequestBuilder.appendInstructions(List.of(sb.toString()));
                log.info("【Reflexion】向 Actor 注入 {} 轮历史反思到 state[{}]", list.size(), stateKey);
            }
            return Optional.empty();
        });
    }

    /**
     * 给反思改进者（Reflector）挂"降级检测"能力 —— 每轮结束后读取 Critic 输出，提取分数；
     * 连续 {@code patience} 轮无提升（或低于 {@code gateThreshold}）时强制 escalate 退出循环。
     *
     * <p>A3 降级机制：避免 Reflexion 循环在质量已收敛/已饱和时仍空转，节省 token。
     *
     * <h3>判定逻辑</h3>
     * <ol>
     *   <li>从 state[{@code criticOutputKey}] 读取 Critic 文本，按 {@code scoreRegex} 提取分数</li>
     *   <li>分数追加到 state[{@code historyStateKey}]（CopyOnWriteArrayList&lt;Double&gt;）</li>
     *   <li>当迭代数 ≥ {@code minIterations} 且最近 {@code patience} 轮均无提升 → escalate(true)</li>
     *   <li>当配置了 {@code gateThreshold}（&gt; 0）且最近 {@code patience} 轮均低于阈值 → escalate(true)</li>
     * </ol>
     *
     * @param ctx              Reflector 的增强上下文
     * @param criticOutputKey  Critic 的 outputKey（其输出已写入 state[criticOutputKey]）
     * @param scoreRegex       提取分数的正则，第一捕获组为 double
     * @param historyStateKey  分数历史存储 key（如 "reflections:rag:scores"）
     * @param patience         容忍连续未提升轮数
     * @param minIterations    至少迭代次数（小于此值不触发降级）
     * @param gateThreshold    质量阈值（≤0 表示不启用阈值降级，仅用"无提升"判定）
     */
    public static void attachReflexionDegradeDetector(AgentEnhancementContext ctx,
                                                     String criticOutputKey,
                                                     String scoreRegex,
                                                     String historyStateKey,
                                                     int patience,
                                                     int minIterations,
                                                     double gateThreshold) {
        final java.util.regex.Pattern compiledScoreRegex;
        try {
            compiledScoreRegex = java.util.regex.Pattern.compile(
                    (scoreRegex == null || scoreRegex.isBlank()) ? "\"score\"\\s*:\\s*([\\d.]+)" : scoreRegex);
        } catch (Exception e) {
            log.error("【Reflexion 降级】scoreRegex 编译失败，降级检测未挂载: {}", scoreRegex, e);
            return;
        }

        ctx.afterAgentCallbackSync(callbackContext -> {
            Object output = callbackContext.state().get(criticOutputKey);
            if (!(output instanceof String criticText) || criticText.isBlank()) {
                log.debug("【Reflexion 降级】Critic 输出为空[outputKey={}]，跳过本轮降级检测", criticOutputKey);
                return Optional.empty();
            }

            Double currentScore = extractScore(criticText, compiledScoreRegex);
            if (currentScore == null) {
                log.debug("【Reflexion 降级】Critic 输出未匹配到分数[regex={}]", compiledScoreRegex.pattern());
                return Optional.empty();
            }

            List<Double> history = getOrCreateScoreHistory(callbackContext, historyStateKey);
            history.add(currentScore);
            callbackContext.state().put(historyStateKey, history);

            int iterCount = history.size();
            log.info("【Reflexion 降级】记录第 {} 轮分数 {}（history={}, threshold={}, patience={}）",
                    iterCount, currentScore, history, gateThreshold, patience);

            if (iterCount < minIterations || history.size() < patience + 1) {
                return Optional.empty();
            }

            boolean noImprove = true;
            boolean belowThreshold = gateThreshold > 0;
            int n = history.size();
            for (int i = n - 1; i >= n - patience; i--) {
                if (history.get(i) > history.get(i - 1)) {
                    noImprove = false;
                }
                if (gateThreshold > 0 && history.get(i) >= gateThreshold) {
                    belowThreshold = false;
                }
            }

            if (noImprove || belowThreshold) {
                EventActions actions = callbackContext.eventActions();
                if (actions != null) {
                    actions.setEscalate(true);
                    log.warn("【Reflexion 降级】触发强制退出（iter={}, score={}, history={}, 原因={}, 阈值={}）",
                            iterCount, currentScore, history,
                            noImprove ? "连续 " + patience + " 轮无提升" : "连续 " + patience + " 轮低于阈值",
                            gateThreshold);
                } else {
                    log.warn("【Reflexion 降级失效】eventActions 为 null，无法触发 escalate（agent 可能不在 LoopAgent 中）");
                }
            }
            return Optional.empty();
        });

        log.info("已为 Reflector 追加 Reflexion 降级检测（criticOutputKey={}, regex={}, patience={}, minIter={}, threshold={}）",
                criticOutputKey, compiledScoreRegex.pattern(), patience, minIterations, gateThreshold);
    }

    /** 从 session state 取或创建反思列表（CopyOnWriteArrayList 保证并发安全） */
    @SuppressWarnings("unchecked")
    private static List<String> getOrCreateReflectionList(CallbackContext callbackContext, String stateKey) {
        Object existing = callbackContext.state().get(stateKey);
        if (existing instanceof List<?> list && !list.isEmpty()) {
            // 已存在且非空，复用（保证跨迭代累积）
            return (List<String>) list;
        }
        return new CopyOnWriteArrayList<>();
    }

    /** 从 session state 取或创建分数历史列表（CopyOnWriteArrayList 保证并发安全） */
    @SuppressWarnings("unchecked")
    private static List<Double> getOrCreateScoreHistory(CallbackContext callbackContext, String stateKey) {
        Object existing = callbackContext.state().get(stateKey);
        if (existing instanceof List<?> list) {
            return (List<Double>) list;
        }
        return new CopyOnWriteArrayList<>();
    }

    /** 用正则从 Critic 文本中提取分数（第一个捕获组） */
    private static Double extractScore(String text, java.util.regex.Pattern scoreRegex) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            java.util.regex.Matcher m = scoreRegex.matcher(text);
            if (m.find() && m.groupCount() >= 1) {
                return Double.parseDouble(m.group(1));
            }
        } catch (Exception e) {
            log.debug("【Reflexion 降级】分数提取失败[regex={}, text={}]", scoreRegex.pattern(),
                    text.substring(0, Math.min(80, text.length())));
        }
        return null;
    }

    /** 从 LlmResponse 提取纯文本（合并所有 Part 的 text） */
    static String extractText(LlmResponse llmResponse) {
        if (llmResponse == null || llmResponse.content().isEmpty()) {
            return null;
        }
        return llmResponse.content()
                .flatMap(Content::parts)
                .map(parts -> parts.stream()
                        .map(Part::text)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .reduce("", String::concat))
                .orElse(null);
    }

    // ============================== A2: 阶段级 OTel Span 上报 ==============================

    /**
     * OTel Tracer 单例（懒加载，未注册 SDK 时 GlobalOpenTelemetry 自动返回 noop）。
     */
    private static volatile Tracer WORKFLOW_TRACER;
    private static final String TRACER_NAME = "agentic-workflow";
    private static final String TRACER_VERSION = "1.0";

    private static Tracer getWorkflowTracer() {
        Tracer t = WORKFLOW_TRACER;
        if (t == null) {
            synchronized (AgenticWorkflowEnhancer.class) {
                t = WORKFLOW_TRACER;
                if (t == null) {
                    try {
                        t = GlobalOpenTelemetry.getTracer(TRACER_NAME, TRACER_VERSION);
                    } catch (Throwable ignored) {
                        t = null;
                    }
                    WORKFLOW_TRACER = t;
                }
            }
        }
        return t;
    }

    /**
     * 给指定子 agent 挂"阶段 span 上报"能力 —— afterAgentCallback 中创建独立 span，
     * 通过 {@code attributeProvider} 让调用者把 state 中的关键信息（如反思轮数、分数历史）写入 span 属性。
     *
     * <p>A2 设计目标：让 Reflexion 每轮迭代 / Dynamic Replan 每次重规划在 observability-server 可见。
     *
     * <h3>OTel 容错</h3>
     * <ul>
     *   <li>未注册 OTel SDK（如单元测试）→ {@link GlobalOpenTelemetry} 返回 noop Tracer，不会抛异常</li>
     *   <li>attributeProvider 抛异常 → 仅记录 debug 日志，不影响工作流执行</li>
     * </ul>
     *
     * @param ctx                 子 agent 的增强上下文
     * @param spanName            span 名称（如 "reflexion.iteration" / "replan.cycle"）
     * @param agentRole           agent 角色（如 "REFLECTOR" / "REPLANNER"）
     * @param attributeProvider   span 属性写入器（callbackContext, span）-> void，可写多属性
     */
    public static void attachSpanEmitter(AgentEnhancementContext ctx,
                                         String spanName,
                                         String agentRole,
                                         BiConsumer<CallbackContext, Span> attributeProvider) {
        ctx.afterAgentCallbackSync(callbackContext -> {
            Tracer tracer = getWorkflowTracer();
            if (tracer == null) {
                return Optional.empty();
            }
            Span span = tracer.spanBuilder(spanName).startSpan();
            try (var scope = span.makeCurrent()) {
                span.setAttribute("agent.role", agentRole);
                span.setAttribute("agent.name", ctx.getAgentName() == null ? "?" : ctx.getAgentName());
                if (attributeProvider != null) {
                    try {
                        attributeProvider.accept(callbackContext, span);
                    } catch (Throwable t) {
                        span.setAttribute("attribute.provider.error", t.getClass().getSimpleName() + ": " + t.getMessage());
                        log.debug("【OTel】span 属性写入失败[span={}]: {}", spanName, t.getMessage());
                    }
                }
            } catch (Throwable t) {
                log.debug("【OTel】span 创建失败[span={}]: {}", spanName, t.getMessage());
            } finally {
                span.end();
            }
            return Optional.empty();
        });

        log.info("已为 agent[{}] 追加阶段 span 上报（spanName={}, role={}）", ctx.getAgentName(), spanName, agentRole);
    }

    /**
     * 便捷版：把 state 中所有以 {@code keyPrefix} 开头的 List 类型属性写入 span（记录 size）。
     *
     * <p>典型用法：{@code attachSpanEmitter(ctx, "reflexion.iteration", "REFLECTOR",
     * stateListSizeAttributes("reflections"))} —— 自动记录反思列表大小。
     */
    public static BiConsumer<CallbackContext, Span> stateListSizeAttributes(String keyPrefix) {
        return (callbackContext, span) -> {
            for (Map.Entry<String, Object> e : callbackContext.state().entrySet()) {
                if (e.getKey().startsWith(keyPrefix) && e.getValue() instanceof List<?> list) {
                    span.setAttribute("state." + e.getKey() + ".size", list.size());
                }
            }
        };
    }

    // ============================== A6: Conditional 谓词短路 ==============================

    /**
     * 给指定 agent 挂"简单 query 短路"能力 —— beforeModelCallback 检查 user query，
     * 匹配 {@code queryPredicateRegex} 时返回固定简短响应跳过 LLM 调用。
     *
     * <p>A6 设计目标：避免对简单 query（如打招呼、确认类）启动昂贵的多轮 Reflexion/ReAct。
     *
     * <h3>短路机制（ADK 0.5.0 标准模式）</h3>
     * <p>beforeModelCallbackSync 返回非空 {@code Optional<LlmResponse>} → ADK 跳过模型调用，
     * 直接使用返回值作为 LlmResponse。后续 Critic 评估"无需返工"→ PASSED → Reflexion loop 退出。
     *
     * <h3>user query 来源</h3>
     * <p>从 {@link CallbackContext#state()} 的 {@code user_query} key 读取（由上层 ChatService 写入）；
     * 找不到时跳过短路（保守策略）。
     *
     * @param ctx                   agent 的增强上下文（通常是 Reflexion 的 Actor）
     * @param queryPredicateRegex   匹配简单 query 的正则（如 {@code ^(你好|hi|hello|在吗|谢谢)}）
     * @param shortCircuitResponse  短路时的固定响应文本（如 "你好，有什么可以帮助您的？"）
     */
    public static void attachQueryPredicateShortCircuit(AgentEnhancementContext ctx,
                                                       String queryPredicateRegex,
                                                       String shortCircuitResponse) {
        final java.util.regex.Pattern compiledPredicate;
        try {
            compiledPredicate = java.util.regex.Pattern.compile(queryPredicateRegex);
        } catch (Exception e) {
            log.error("【A6 短路】queryPredicateRegex 编译失败，短路能力未挂载: {}", queryPredicateRegex, e);
            return;
        }
        final String responseText = (shortCircuitResponse == null || shortCircuitResponse.isBlank())
                ? "好的，已为您处理。" : shortCircuitResponse;

        ctx.beforeModelCallbackSync((callbackContext, llmRequestBuilder) -> {
            Object queryObj = callbackContext.state().get("user_query");
            if (!(queryObj instanceof String queryText) || queryText.isBlank()) {
                return Optional.empty();
            }
            try {
                if (compiledPredicate.matcher(queryText.trim()).find()) {
                    LlmResponse shortCircuit = LlmResponse.builder()
                            .content(Content.fromParts(Part.fromText(responseText)))
                            .build();
                    log.info("【A6 短路】query 命中谓词[regex={}, query={}]，返回短路响应跳过 LLM 调用",
                            compiledPredicate.pattern(),
                            queryText.substring(0, Math.min(50, queryText.length())));
                    return Optional.of(shortCircuit);
                }
            } catch (Throwable t) {
                log.debug("【A6 短路】谓词匹配异常[regex={}]: {}", compiledPredicate.pattern(), t.getMessage());
            }
            return Optional.empty();
        });

        log.info("已为 agent[{}] 追加 A6 谓词短路（regex={}, response={}）",
                ctx.getAgentName(), compiledPredicate.pattern(),
                responseText.substring(0, Math.min(30, responseText.length())));
    }

    // ============================== P0: Human-in-the-Loop 审批门控 ==============================

    /** HITL 审批状态：待审批 */
    private static final String HITL_STATUS_PENDING = "pending";

    /** HITL 审批状态：已批准 */
    private static final String HITL_STATUS_APPROVED = "approved";

    /** HITL 审批状态：已拒绝 */
    private static final String HITL_STATUS_REJECTED = "rejected";

    /** HITL Session state key：审批状态 */
    private static final String HITL_STATE_KEY_STATUS = "approval_status";

    /** HITL Session state key：审批请求信息 */
    private static final String HITL_STATE_KEY_REQUEST = "approval_request";

    /**
     * 给审批门控 agent 挂"人工审批"能力 —— beforeModelCallback 检查审批状态，
     * 未审批时创建审批请求并暂停执行，已审批时根据结果继续或退出。
     *
     * <p>P0 设计目标：让关键操作（如删除数据、发送邮件、修改配置）在执行前暂停，
     * 等待人工审批，避免 AI 自主决策带来的风险。
     *
     * <h3>审批流程</h3>
     * <ol>
     *   <li>beforeModelCallback 检查 state["approval_status"]</li>
     *   <li>null → 创建审批请求，设置 status="pending"，返回 LlmResponse 暂停执行</li>
     *   <li>"pending" → 继续暂停，等待外部系统更新状态</li>
     *   <li>"approved" → 清除状态，返回 Optional.empty() 继续执行</li>
     *   <li>"rejected" → 返回拒绝响应，触发退出或重规划</li>
     * </ol>
     *
     * <h3>外部审批接口</h3>
     * <p>外部系统（Web UI / IM / Email）通过 API 更新审批状态：
     * <pre>
     * POST /api/v1/approval/{sessionId}/approve
     * POST /api/v1/approval/{sessionId}/reject
     * </pre>
     *
     * @param ctx                   审批 agent 的增强上下文
     * @param approvalChannel       审批渠道（web/feishu/email/dingtalk）
     * @param approvalTimeoutSeconds 审批超时时间（秒）
     * @param riskLevel             风险等级（high/medium/low）
     */
    public static void attachApprovalGate(AgentEnhancementContext ctx,
                                         String approvalChannel,
                                         int approvalTimeoutSeconds,
                                         String riskLevel) {
        ctx.beforeModelCallbackSync((callbackContext, llmRequestBuilder) -> {
            Object statusObj = callbackContext.state().get(HITL_STATE_KEY_STATUS);
            String status = statusObj instanceof String ? (String) statusObj : null;

            if (status == null) {
                // 首次进入：创建审批请求
                String requestId = java.util.UUID.randomUUID().toString();
                long requestTime = System.currentTimeMillis();
                long timeoutTime = requestTime + (approvalTimeoutSeconds * 1000L);

                // 构建审批请求信息
                String approvalRequest = buildApprovalRequest(
                        requestId, approvalChannel, riskLevel,
                        callbackContext.state(), requestTime, timeoutTime);

                // 存储审批请求信息
                callbackContext.state().put(HITL_STATE_KEY_REQUEST, approvalRequest);
                callbackContext.state().put(HITL_STATE_KEY_STATUS, HITL_STATUS_PENDING);
                callbackContext.state().put("approval_request_id", requestId);
                callbackContext.state().put("approval_timeout_time", timeoutTime);

                log.info("【HITL】创建审批请求：requestId={}, channel={}, riskLevel={}, timeout={}s",
                        requestId, approvalChannel, riskLevel, approvalTimeoutSeconds);

                // 发送审批通知（根据渠道）
                sendApprovalNotification(requestId, approvalChannel, approvalRequest);

                // 返回暂停响应，让 agent 输出审批等待信息
                LlmResponse pendingResponse = LlmResponse.builder()
                        .content(Content.fromParts(Part.fromText(
                                "⏳ 已提交审批请求（" + requestId + "），等待人工审批...\n" +
                                "审批渠道：" + approvalChannel + "\n" +
                                "风险等级：" + riskLevel + "\n" +
                                "超时时间：" + approvalTimeoutSeconds + "秒")))
                        .build();
                return Optional.of(pendingResponse);

            } else if (HITL_STATUS_PENDING.equals(status)) {
                // 检查是否超时
                Object timeoutObj = callbackContext.state().get("approval_timeout_time");
                if (timeoutObj instanceof Long timeoutTime && System.currentTimeMillis() > timeoutTime) {
                    log.warn("【HITL】审批超时：requestId={}", callbackContext.state().get("approval_request_id"));
                    callbackContext.state().put(HITL_STATE_KEY_STATUS, HITL_STATUS_REJECTED);
                    callbackContext.state().put("approval_reject_reason", "审批超时");

                    LlmResponse timeoutResponse = LlmResponse.builder()
                            .content(Content.fromParts(Part.fromText(
                                    "❌ 审批超时，操作已取消。")))
                            .build();
                    return Optional.of(timeoutResponse);
                }

                // 继续等待审批
                LlmResponse waitingResponse = LlmResponse.builder()
                        .content(Content.fromParts(Part.fromText(
                                "⏳ 等待审批中...（" + callbackContext.state().get("approval_request_id") + "）")))
                        .build();
                return Optional.of(waitingResponse);

            } else if (HITL_STATUS_APPROVED.equals(status)) {
                // 审批通过：清除状态，继续执行
                log.info("【HITL】审批通过：requestId={}", callbackContext.state().get("approval_request_id"));
                callbackContext.state().remove(HITL_STATE_KEY_STATUS);
                callbackContext.state().remove(HITL_STATE_KEY_REQUEST);
                callbackContext.state().remove("approval_request_id");
                callbackContext.state().remove("approval_timeout_time");
                return Optional.empty();

            } else if (HITL_STATUS_REJECTED.equals(status)) {
                // 审批拒绝：返回拒绝响应
                String rejectReason = callbackContext.state().get("approval_reject_reason") != null
                        ? (String) callbackContext.state().get("approval_reject_reason")
                        : "未提供拒绝原因";
                log.info("【HITL】审批拒绝：requestId={}, reason={}",
                        callbackContext.state().get("approval_request_id"), rejectReason);

                LlmResponse rejectedResponse = LlmResponse.builder()
                        .content(Content.fromParts(Part.fromText(
                                "❌ 审批被拒绝。\n原因：" + rejectReason)))
                        .build();
                return Optional.of(rejectedResponse);
            }

            return Optional.empty();
        });

        log.info("已为 agent[{}] 追加 P0 审批门控（channel={}, timeout={}s, riskLevel={})",
                ctx.getAgentName(), approvalChannel, approvalTimeoutSeconds, riskLevel);
    }

    /**
     * 构建审批请求信息（JSON 格式）
     */
    private static String buildApprovalRequest(String requestId, String channel, String riskLevel,
                                               Map<String, Object> state, long requestTime, long timeoutTime) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"requestId\": \"").append(requestId).append("\",\n");
        sb.append("  \"channel\": \"").append(channel).append("\",\n");
        sb.append("  \"riskLevel\": \"").append(riskLevel).append("\",\n");
        sb.append("  \"requestTime\": ").append(requestTime).append(",\n");
        sb.append("  \"timeoutTime\": ").append(timeoutTime).append(",\n");
        sb.append("  \"context\": {\n");

        // 提取关键上下文信息
        int count = 0;
        for (Map.Entry<String, Object> entry : state.entrySet()) {
            if (count >= 5) break; // 最多包含 5 个上下文项
            if (entry.getValue() instanceof String strVal && strVal.length() < 200) {
                sb.append("    \"").append(entry.getKey()).append("\": \"")
                        .append(strVal.replace("\"", "\\\"")).append("\",\n");
                count++;
            }
        }

        sb.append("    \"_truncated\": ").append(count >= 5).append("\n");
        sb.append("  }\n");
        sb.append("}");
        return sb.toString();
    }

    /**
     * 发送审批通知（根据渠道）
     * <p>
     * 当前实现：仅记录日志。实际生产环境需要对接具体渠道（飞书/邮件/钉钉等）。
     */
    private static void sendApprovalNotification(String requestId, String channel, String approvalRequest) {
        switch (channel.toLowerCase()) {
            case "web":
                log.info("【HITL】Web UI 审批通知：requestId={}", requestId);
                // TODO: 通过 WebSocket 推送给前端
                break;
            case "feishu":
                log.info("【HITL】飞书审批通知：requestId={}", requestId);
                // TODO: 调用飞书 API 发送消息
                break;
            case "email":
                log.info("【HITL】邮件审批通知：requestId={}", requestId);
                // TODO: 调用邮件服务发送审批邮件
                break;
            case "dingtalk":
                log.info("【HITL】钉钉审批通知：requestId={}", requestId);
                // TODO: 调用钉钉 API 发送消息
                break;
            default:
                log.warn("【HITL】未知审批渠道：{}", channel);
        }
    }

    /**
     * 提供外部 API 调用的审批接口（静态方法，供 Controller 调用）
     *
     * @param sessionId 会话 ID
     * @param approved  是否批准
     * @param reason    拒绝原因（仅拒绝时需要）
     */
    public static void handleApproval(String sessionId, boolean approved, String reason) {
        // 此方法需要配合 SessionManager 使用，从 session state 中读取/更新审批状态
        // 实际实现需要注入 SessionManager 或类似的会话管理服务
        log.info("【HITL】审批处理：sessionId={}, approved={}, reason={}", sessionId, approved, reason);
        // TODO: 通过 SessionManager 更新 state["approval_status"]
    }

}

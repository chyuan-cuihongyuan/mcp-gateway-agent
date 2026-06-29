package cn.chyuan.ai.infrastructure.utils;

import cn.chyuan.ai.observability.client.ObservabilityClient;
import cn.chyuan.ai.observability.client.model.AgentDecisionReport;
import cn.chyuan.ai.observability.client.model.ChatResultReport;
import cn.chyuan.ai.observability.client.model.ToolCallLogReport;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * MCP 网关可观测性上报工具
 */
@Slf4j
@Component
public class ObservabilityHelper {

    private static final String SOURCE_SERVICE = "GATEWAY";

    @Resource
    private ObservabilityClient observabilityClient;

    /**
     * 上报工具调用
     */
    public void reportToolCall(String traceId, String gatewayId, String toolName,
                               String status, Integer costTimeMs, String errorMessage) {
        try {
            ToolCallLogReport report = ToolCallLogReport.builder()
                    .traceId(traceId).spanId(gatewayId)
                    .toolName(toolName).status(status)
                    .costTimeMs(costTimeMs).errorMessage(errorMessage)
                    .build();
            observabilityClient.reportToolCall(report);
        } catch (Throwable e) {
            log.debug("observability report failed: {}", e.getMessage());
        }
    }

    /**
     * 上报 Agent 决策（MCP 网关场景）
     */
    public void reportAgentDecision(String traceId, String sessionId, String userId,
                                    String agentId, String userQuery, String branchType,
                                    String intentType, String selectedToolList,
                                    String decisionReason, String planSteps,
                                    int toolCallTimes, int toolRetryTimes,
                                    String status, int costTimeMs, String modelVersion,
                                    String errorMessage) {
        try {
            AgentDecisionReport report = AgentDecisionReport.builder()
                    .traceId(traceId)
                    .sourceService(SOURCE_SERVICE)
                    .ownerUserId(userId)
                    .sessionId(sessionId)
                    .agentId(agentId)
                    .userQuery(userQuery)
                    .intentType(intentType)
                    .selectedToolList(selectedToolList)
                    .decisionReason(decisionReason)
                    .branchType(branchType)
                    .planSteps(planSteps)
                    .toolCallTimes(toolCallTimes)
                    .toolRetryTimes(toolRetryTimes)
                    .agentStatus(status)
                    .costTimeMs(costTimeMs)
                    .modelVersion(modelVersion)
                    .errorMessage(errorMessage)
                    .build();
            observabilityClient.reportAgentDecision(report);
        } catch (Throwable e) {
            log.debug("observability agent decision report failed: {}", e.getMessage());
        }
    }

    /**
     * 上报问答结果（MCP 网关场景）
     */
    public void reportChatResult(String traceId, String sessionId, String userId,
                                 String question, String answer,
                                 int promptTokens, int completionTokens,
                                 String status, int costTimeMs, String modelVersion) {
        try {
            ChatResultReport report = ChatResultReport.builder()
                    .traceId(traceId)
                    .sourceService(SOURCE_SERVICE)
                    .ownerUserId(userId)
                    .sessionId(sessionId)
                    .question(question)
                    .answer(answer)
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .finalStatus(status)
                    .totalCostTimeMs(costTimeMs)
                    .modelVersion(modelVersion)
                    .build();
            observabilityClient.reportChatResult(report);
        } catch (Throwable e) {
            log.debug("observability chat result report failed: {}", e.getMessage());
        }
    }
}

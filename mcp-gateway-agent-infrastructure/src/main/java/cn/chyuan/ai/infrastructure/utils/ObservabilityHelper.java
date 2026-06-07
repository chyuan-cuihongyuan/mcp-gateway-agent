package cn.chyuan.ai.infrastructure.utils;

import cn.chyuan.ai.observability.client.ObservabilityClient;
import cn.chyuan.ai.observability.client.model.AgentDecisionReport;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ObservabilityHelper {

    private static final String SOURCE_SERVICE = "GATEWAY";

    @Resource
    private ObservabilityClient observabilityClient;

    public void reportToolCall(String traceId, String gatewayId, String toolName,
                               String status, Integer costTimeMs, String errorMessage) {
        try {
            AgentDecisionReport report = AgentDecisionReport.builder()
                    .traceId(traceId).sourceService(SOURCE_SERVICE)
                    .sessionId(traceId).agentId(gatewayId)
                    .userQuery(toolName).intentType(toolName)
                    .branchType("TOOL_CALL").agentStatus(status)
                    .toolCallTimes(1).costTimeMs(costTimeMs).errorMessage(errorMessage)
                    .build();
            observabilityClient.reportAgentDecision(report);
        } catch (Exception e) {
            log.debug("observability report failed: {}", e.getMessage());
        }
    }
}

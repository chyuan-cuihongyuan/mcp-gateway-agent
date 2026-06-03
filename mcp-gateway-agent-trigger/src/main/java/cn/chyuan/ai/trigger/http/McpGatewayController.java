package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.IMcpGatewayService;
import cn.chyuan.ai.cases.mcp.IMcpMessageService;
import cn.chyuan.ai.cases.mcp.IMcpSessionService;
import cn.chyuan.ai.domain.session.model.entity.HandleMessageCommandEntity;
import cn.chyuan.ai.domain.session.model.valobj.McpSchemaVO;
import cn.chyuan.ai.domain.session.model.valobj.SessionConfigVO;
import cn.chyuan.ai.domain.session.service.ISessionManagementService;
import cn.chyuan.ai.domain.session.service.ISessionMessageService;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.infrastructure.utils.ObservabilityHelper;
import cn.chyuan.ai.types.exception.AppException;
import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import jakarta.annotation.Resource;

/**
 * MCP 网关服务接口管理
 *
 * @author chyuan @chyuan
 * 2025/12/13 08:54
 */
@Slf4j
@RestController
@CrossOrigin(origins = {"${cors.allowed-origins:http://localhost:3000}"}, allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
@RequestMapping("/api-gateway")
public class McpGatewayController implements IMcpGatewayService {

    private static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");
    private static final Pattern MCP_METHOD_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_./:-]{0,127}$");
    private static final int MAX_MESSAGE_BODY_LENGTH = 64 * 1024;

    @Resource
    private IMcpSessionService mcpSessionService;

    @Resource
    private IMcpMessageService mcpMessageService;

    @Resource
    private ObservabilityHelper observabilityHelper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 处理 sse 连接，创建会
     * <br/>
     * <a href="http://localhost:8777/api-gateway/gateway_001/mcp/sse">http://localhost:8777/api-gateway/gateway_001/mcp/sse</a>
     * <br/>
     * <a href="http://localhost:8777/api-gateway/gateway_001/mcp/sse?api_key=gw-lf3HFzlJCdnrYl20oHbd5lJQxE7GWz8wjsSgjDZfctJNV8s5">http://localhost:8777/api-gateway/gateway_001/mcp/sse?api_key=gw-lf3HFzlJCdnrYl20oHbd5lJQxE7GWz8wjsSgjDZfctJNV8s5</a>
     *
     * @param gatewayId 网关ID
     */
    @GetMapping(value = "{gatewayId}/mcp/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Override
    public Flux<ServerSentEvent<String>> handleSseConnection(
            @PathVariable("gatewayId") String gatewayId, @RequestParam(value = "api_key", required = false, defaultValue = "") String apiKey) throws Exception {
        try {
            log.info("建立 MCP SSE 连接，gatewayId:{}", gatewayId);
            validateId("gatewayId", gatewayId);

            return mcpSessionService.createMcpSession(gatewayId, apiKey);
        } catch (AppException e) {
            log.error("建立 MCP SSE 连接拒绝，gatewayId: {}", gatewayId, e);
            return Flux.just(ServerSentEvent.<String>builder()
                    .id(UUID.randomUUID().toString())
                    .event("error")
                    .data(JSON.toJSONString(Response.<String>builder()
                            .code(e.getCode())
                            .info(e.getInfo())
                            .build()))
                    .build());
        } catch (Exception e) {
            log.error("建立 MCP SSE 连接失败，gatewayId: {}", gatewayId, e);
            throw e;
        }
    }

    /**
     * 处理 sse 消息，响应会
     *
     * @param gatewayId   网关ID
     * @param sessionId   会话ID
     * @param messageBody 请求消息
     * @return 响应结果
     * <br/>
     * {
     * "jsonrpc": "2.0",
     * "method": "initialize",
     * "id": "95835f74-0",
     * "params": {
     * "protocolVersion": "2024-11-05",
     * "capabilities": {},
     * "clientInfo": {
     * "name": "Java SDK MCP Client",
     * "version": "1.0.0"
     * }
     * }
     * }
     */
    @PostMapping(value = "{gatewayId}/mcp/sse", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Void>> handleMessage(@PathVariable("gatewayId") String gatewayId,
                                                    @RequestParam("sessionId") String sessionId,
                                                    @RequestParam(value = "api_key", required = false, defaultValue = "") String apiKey,
                                                    @RequestBody String messageBody) {
        try {
            log.info("处理 MCP SSE 消息，gatewayId:{} apiKey:{} sessionId:{} messageBody:{}", gatewayId, apiKey, sessionId, messageBody);
            validateId("gatewayId", gatewayId);
            validateId("sessionId", sessionId);
            validateMessageBody(messageBody);

            HandleMessageCommandEntity commandEntity = new HandleMessageCommandEntity(gatewayId, apiKey, sessionId, messageBody);
            ResponseEntity<Void> responseEntity = mcpMessageService.handleMessage(commandEntity);

            observabilityHelper.reportToolCall(sessionId, gatewayId, "handleMessage", "SUCCESS", null, null);
            return Mono.just(responseEntity);
        } catch (AppException e) {
            log.warn("处理 MCP SSE 消息参数非法，gatewayId:{} sessionId:{} reason:{}", gatewayId, sessionId, e.getInfo());
            observabilityHelper.reportToolCall(sessionId, gatewayId, "handleMessage", "FAIL", null, e.getInfo());
            return Mono.just(ResponseEntity.badRequest().build());
        } catch (Exception e) {
            log.error("处理 MCP SSE 消息失败，gatewayId:{} sessionId:{} messageBody:{}", gatewayId, sessionId, messageBody, e);
            observabilityHelper.reportToolCall(sessionId, gatewayId, "handleMessage", "FAIL", null, e.getMessage());
            return Mono.just(ResponseEntity.internalServerError().build());
        }
    }

    private void validateId(String fieldName, String value) {
        if (StringUtils.isBlank(value) || !ID_PATTERN.matcher(value).matches()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), fieldName + "格式非法");
        }
    }

    private void validateMessageBody(String messageBody) {
        if (StringUtils.isBlank(messageBody)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "messageBody不能为空");
        }
        if (messageBody.length() > MAX_MESSAGE_BODY_LENGTH) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "messageBody超过64KB限制");
        }
        try {
            JsonNode root = objectMapper.readTree(messageBody);
            JsonNode methodNode = root.get("method");
            if (methodNode != null && !methodNode.isNull()) {
                String method = methodNode.asText();
                if (StringUtils.isBlank(method) || !MCP_METHOD_PATTERN.matcher(method).matches()) {
                    throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "method格式非法");
                }
                if ("tools/call".equals(method)) {
                    JsonNode toolNameNode = root.path("params").path("name");
                    if (!toolNameNode.isTextual() || !MCP_METHOD_PATTERN.matcher(toolNameNode.asText()).matches()) {
                        throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "toolName格式非法");
                    }
                }
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "messageBody不是合法JSON");
        }
    }

}

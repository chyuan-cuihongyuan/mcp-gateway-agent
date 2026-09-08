package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import cn.chyuan.ai.domain.session.adapter.repository.ISessionMetaRepository;
import cn.chyuan.ai.domain.session.model.valobj.SessionMetaVO;
import cn.chyuan.ai.domain.session.service.tool.IMcpToolCatalogService;
import cn.chyuan.ai.domain.usage.model.valobj.UsageRecordVO;
import cn.chyuan.ai.domain.usage.service.IUsageLedgerService;
import cn.chyuan.ai.infrastructure.gateway.streamable.GatewayMcpServerRegistry;
import cn.chyuan.ai.infrastructure.utils.ObservabilityHelper;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Streamable HTTP 委派路由（工单 0020）
 *
 * <p>/api-gateway/{gatewayId}/mcp 单端点：GET（SSE 监听流）/POST（initialize、
 * tools/list、tools/call、ping、通知）/DELETE（会话终止）。
 * 认证（order 10）与配额（order 11）过滤器先行，天然覆盖全部方法。
 *
 * <p>协议主体（会话生命周期、initialize 协商、SSE 回包、DELETE）委派给
 * {@link GatewayMcpServerRegistry} 的官方 McpSyncServer 传输；治理生效点在委派层：
 * tools/list 按 CEL 过滤后自答（application/json），tools/call 未知工具直答 -32003，
 * 已知工具委派官方分发（wrapper 内 CEL 拦截 -32006 + 观测）。
 *
 * <p>会话语义与旧 SSE 实现等价：本地会话表 + Redis 元数据镜像（TTL
 * {@code mcp.session.timeout-minutes}），过期/未知会话统一 404；委派回包 404 时
 * 清理陈旧本地条目（重启后 Redis 残留自愈）。
 *
 * @author chyuan
 */
@Slf4j
public class McpGatewayDelegateServlet extends HttpServlet {

    private static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");
    private static final Pattern MCP_METHOD_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_./:-]{0,127}$");
    private static final int MAX_MESSAGE_BODY_LENGTH = 64 * 1024;
    private static final String SESSION_HEADER = "Mcp-Session-Id";

    private final GatewayMcpServerRegistry registry;
    private final IMcpToolCatalogService toolCatalogService;
    private final ObservabilityHelper observabilityHelper;
    private final IUsageLedgerService usageLedgerService;
    private final long sessionTimeoutMinutes;

    /** 本地会话表：sessionId → 最后访问时间（TTL 权威，与旧实现的内存会话等价） */
    private final ConcurrentHashMap<String, Long> localSessions = new ConcurrentHashMap<>();

    /** Redis 会话元数据镜像（可选：无 Redis 时不影响单实例语义） */
    private final ISessionMetaRepository sessionMetaRepository;

    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mcp-streamable-session-cleanup");
        thread.setDaemon(true);
        return thread;
    });

    private final ObjectMapper objectMapper = new ObjectMapper();

    public McpGatewayDelegateServlet(GatewayMcpServerRegistry registry,
            IMcpToolCatalogService toolCatalogService,
            ISessionMetaRepository sessionMetaRepository,
            ObservabilityHelper observabilityHelper,
            IUsageLedgerService usageLedgerService,
            long sessionTimeoutMinutes) {
        this.registry = registry;
        this.toolCatalogService = toolCatalogService;
        this.sessionMetaRepository = sessionMetaRepository;
        this.observabilityHelper = observabilityHelper;
        this.usageLedgerService = usageLedgerService;
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
        cleanupScheduler.scheduleAtFixedRate(this::cleanupExpiredSessions, 5, 5, TimeUnit.MINUTES);
        log.info("Streamable HTTP 委派路由已初始化: sessionTimeoutMinutes={}", sessionTimeoutMinutes);
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String pathInfo = request.getPathInfo();
        // 期望 /{gatewayId}/mcp
        if (pathInfo == null || !pathInfo.startsWith("/") || pathInfo.length() < 3) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String[] segments = pathInfo.substring(1).split("/");
        if (segments.length != 2 || !"mcp".equals(segments[1])
                || !ID_PATTERN.matcher(segments[0]).matches()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String gatewayId = segments[0];

        try {
            String method = request.getMethod();
            switch (method) {
                case "GET", "DELETE" -> handleStreamOrTerminate(request, response, gatewayId, method);
                case "POST" -> handlePost(request, response, gatewayId);
                default -> response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            }
        } catch (AppException e) {
            // 网关不存在等业务拒绝
            log.warn("Streamable HTTP 请求拒绝: gatewayId={}, reason={}", gatewayId, e.getInfo());
            writeJsonRpcError(response, HttpServletResponse.SC_NOT_FOUND,
                    jsonRpcErrorCode(e), e.getInfo(), null);
        }
    }

    /** AppException 携带 JSON-RPC 数值码时透传，否则按内部错误（与 registry 同一口径） */
    private static int jsonRpcErrorCode(AppException e) {
        try {
            return Integer.parseInt(e.getCode());
        } catch (NumberFormatException ignore) {
            return McpErrorCodes.INTERNAL_ERROR;
        }
    }

    /** GET（SSE 监听流）/ DELETE（会话终止）：会话把关后委派官方传输 */
    private void handleStreamOrTerminate(HttpServletRequest request, HttpServletResponse response,
            String gatewayId, String httpMethod) throws IOException {
        if (!sessionAllowed(request)) {
            writeSessionNotFound(request, response);
            return;
        }
        StatusObservingResponseWrapper wrapper = new StatusObservingResponseWrapper(response);
        delegate(request, wrapper, gatewayId);
        if ("DELETE".equals(httpMethod)) {
            String sessionId = request.getHeader(SESSION_HEADER);
            removeSession(sessionId);
        } else if (wrapper.status() == HttpServletResponse.SC_NOT_FOUND) {
            // 官方侧无此会话（如重启后）：清理陈旧本地条目
            removeSession(request.getHeader(SESSION_HEADER));
        }
    }

    private void handlePost(HttpServletRequest request, HttpServletResponse response,
            String gatewayId) throws IOException {
        byte[] bodyBytes = readBody(request);
        if (bodyBytes == null) {
            writeJsonRpcError(response, HttpServletResponse.SC_BAD_REQUEST,
                    McpErrorCodes.INVALID_REQUEST, "messageBody超过64KB限制", null);
            return;
        }
        String messageBody = new String(bodyBytes, StandardCharsets.UTF_8);
        JsonNode root = parseAndValidate(messageBody, response);
        if (root == null) {
            return;
        }
        JsonNode methodNode = root.get("method");
        String mcpMethod = methodNode == null || methodNode.isNull() ? null : methodNode.asText();

        if (mcpMethod != null && "initialize".equals(mcpMethod)) {
            handleInitialize(request, response, gatewayId, bodyBytes);
            return;
        }

        // 非 initialize 请求需携带有效会话
        if (!sessionAllowed(request)) {
            writeSessionNotFound(request, response);
            return;
        }

        if (mcpMethod != null && "tools/list".equals(mcpMethod)) {
            handleToolsList(request, response, gatewayId, root);
            return;
        }
        if (mcpMethod != null && "tools/call".equals(mcpMethod)
                && !toolCatalogService.toolExists(gatewayId, toolNameOf(root))) {
            // 未知工具在委派层直答 -32003（保持与 0018 契约一致的错误码语义）
            reportAndWriteToolCallFailure(request, response, gatewayId, root,
                    McpErrorCodes.TOOL_NOT_FOUND, "工具未找到: " + toolNameOf(root));
            return;
        }

        // 其余方法（tools/call 已知工具、ping、notifications/* 等）委派官方服务器分发
        HttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request, bodyBytes);
        StatusObservingResponseWrapper wrapper = new StatusObservingResponseWrapper(response);
        delegate(cachedRequest, wrapper, gatewayId);
        if (wrapper.status() == HttpServletResponse.SC_NOT_FOUND) {
            removeSession(request.getHeader(SESSION_HEADER));
        }
    }

    /** initialize：委派官方传输建会话；Mcp-Session-Id 响应头一旦写出立即登记（早于响应体到达客户端，避免后续请求竞态 404） */
    private void handleInitialize(HttpServletRequest request, HttpServletResponse response,
            String gatewayId, byte[] bodyBytes) throws IOException {
        String connectTraceId = UUID.randomUUID().toString().replace("-", "");
        long start = System.currentTimeMillis();
        HttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request, bodyBytes);
        SessionCapturingResponseWrapper wrapper = new SessionCapturingResponseWrapper(response, sessionId -> {
            try {
                registerSession(sessionId, gatewayId, principalOf(request));
            } catch (Exception e) {
                log.warn("登记 Streamable 会话失败: gatewayId={}, sessionId={}", gatewayId, sessionId);
            }
        });
        try {
            delegate(cachedRequest, wrapper, gatewayId);
        } catch (Exception e) {
            reportConnect(connectTraceId, gatewayId, "FAIL",
                    (int) (System.currentTimeMillis() - start), e.getMessage());
            throw e;
        }
        int costMs = (int) (System.currentTimeMillis() - start);
        String sessionId = wrapper.capturedSessionId();
        if (sessionId != null && wrapper.status() < HttpServletResponse.SC_BAD_REQUEST) {
            reportConnect(sessionId, gatewayId, "SUCCESS", costMs, null);
            recordUsage(request, gatewayId, "initialize", "SUCCESS", costMs);
        } else {
            reportConnect(connectTraceId, gatewayId, "FAIL", costMs,
                    "initialize未建立会话(httpStatus=" + wrapper.status() + ")");
            recordUsage(request, gatewayId, "initialize", "FAIL", costMs);
        }
    }

    /** tools/list 生效点：CEL 过滤后以 application/json 自答（官方传输对 request 走 SSE 回包，无法在响应侧过滤） */
    private void handleToolsList(HttpServletRequest request, HttpServletResponse response,
            String gatewayId, JsonNode root) throws IOException {
        long start = System.currentTimeMillis();
        try {
            GovernancePrincipal principal = principalOf(request);
            var tools = toolCatalogService.visibleTools(gatewayId, principal, "tools/list");

            ObjectNode responseBody = objectMapper.createObjectNode();
            responseBody.put("jsonrpc", "2.0");
            responseBody.set("id", idOf(root));
            ObjectNode result = responseBody.putObject("result");
            ArrayNode toolsArray = result.putArray("tools");
            tools.forEach(tool -> toolsArray.add(objectMapper.valueToTree(tool)));

            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(objectMapper.writeValueAsString(responseBody));
            response.getWriter().flush();

            reportListInvocation(request, gatewayId, "SUCCESS",
                    (int) (System.currentTimeMillis() - start), null);
            recordUsage(request, gatewayId, "tools/list", "SUCCESS",
                    (int) (System.currentTimeMillis() - start));
        } catch (Exception e) {
            reportListInvocation(request, gatewayId, "FAIL",
                    (int) (System.currentTimeMillis() - start), e.getMessage());
            recordUsage(request, gatewayId, "tools/list", "FAIL",
                    (int) (System.currentTimeMillis() - start));
            writeJsonRpcError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    McpErrorCodes.INTERNAL_ERROR, "内部错误: " + e.getMessage(), idOf(root));
        }
    }

    /** tools/list 观测上报（口径承接旧 SSE handleMessage 上报） */
    private void reportListInvocation(HttpServletRequest request, String gatewayId, String status,
            int costMs, String errorMessage) {
        String sessionId = request.getHeader(SESSION_HEADER);
        observabilityHelper.reportToolCall(sessionId, gatewayId, "tools/list", status, costMs, errorMessage);
        observabilityHelper.reportAgentDecision(sessionId, sessionId, null, gatewayId,
                "tools/list", "TOOL_CALL", "tool_invocation", null, null, null,
                1, 0, status, costMs, null, errorMessage);
    }

    /** 请求 id 原样取回（缺失为 null） */
    private static JsonNode idOf(JsonNode root) {
        JsonNode id = root.get("id");
        return id == null || id.isNull() ? null : id;
    }

    /** tools/call 请求的工具名（缺失为 null） */
    private static String toolNameOf(JsonNode root) {
        JsonNode name = root.path("params").path("name");
        return name.isTextual() ? name.asText() : null;
    }

    @Override
    public void destroy() {
        cleanupScheduler.shutdownNow();
    }

    private void delegate(HttpServletRequest request, HttpServletResponse response, String gatewayId)
            throws IOException {
        try {
            registry.transportOf(gatewayId).service(request, response);
        } catch (IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("官方传输处理失败: " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------------
    // 会话生命周期（本地表权威 + Redis 镜像，TTL 与旧实现等价）
    // ---------------------------------------------------------------------

    private boolean sessionAllowed(HttpServletRequest request) {
        String sessionId = request.getHeader(SESSION_HEADER);
        if (StringUtils.isBlank(sessionId)) {
            // 官方传输要求非 initialize 请求必须携带会话；此处先行 404，行为与其一致
            return false;
        }
        Long lastAccessed = localSessions.get(sessionId);
        if (lastAccessed == null) {
            return false;
        }
        if (isExpired(lastAccessed)) {
            removeSession(sessionId);
            return false;
        }
        localSessions.put(sessionId, System.currentTimeMillis());
        if (sessionMetaRepository != null) {
            try {
                sessionMetaRepository.touch(sessionId, Duration.ofMinutes(sessionTimeoutMinutes));
            } catch (Exception e) {
                // 镜像失败不影响本地会话语义
                log.debug("touch session meta failed: sessionId={}", sessionId);
            }
        }
        return true;
    }

    private void registerSession(String sessionId, String gatewayId, GovernancePrincipal principal) {
        localSessions.put(sessionId, System.currentTimeMillis());
        if (sessionMetaRepository != null) {
            try {
                SessionMetaVO meta = SessionMetaVO.builder()
                        .sessionId(sessionId)
                        .gatewayId(gatewayId)
                        // principal 携带的已是 SHA-256 哈希（0017），直接沿用避免口径分裂
                        .apiKeyHash(principal == null || principal.getApiKeyHash() == null
                                ? "" : principal.getApiKeyHash())
                        .createTime(System.currentTimeMillis())
                        .lastAccessedTime(System.currentTimeMillis())
                        .status("ACTIVE")
                        .build();
                sessionMetaRepository.save(meta, Duration.ofMinutes(sessionTimeoutMinutes));
            } catch (Exception e) {
                log.debug("save session meta failed: sessionId={}", sessionId);
            }
        }
        log.info("登记 Streamable 会话: gatewayId={}, sessionId={}, 当前活跃会话数={}",
                gatewayId, sessionId, localSessions.size());
    }

    private void removeSession(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return;
        }
        localSessions.remove(sessionId);
        if (sessionMetaRepository != null) {
            try {
                sessionMetaRepository.delete(sessionId);
            } catch (Exception e) {
                log.debug("delete session meta failed: sessionId={}", sessionId);
            }
        }
    }

    private void cleanupExpiredSessions() {
        int cleaned = 0;
        for (Map.Entry<String, Long> entry : localSessions.entrySet()) {
            if (isExpired(entry.getValue())) {
                removeSession(entry.getKey());
                cleaned++;
            }
        }
        if (cleaned > 0) {
            log.info("清理了 {} 个过期 Streamable 会话，剩余活跃会话数: {}", cleaned, localSessions.size());
        }
    }

    private boolean isExpired(long lastAccessed) {
        return System.currentTimeMillis() - lastAccessed >= TimeUnit.MINUTES.toMillis(sessionTimeoutMinutes);
    }

    // ---------------------------------------------------------------------
    // 工具方法
    // ---------------------------------------------------------------------

    private GovernancePrincipal principalOf(HttpServletRequest request) {
        Object principal = request.getAttribute(GovernancePrincipal.REQUEST_ATTR);
        return principal instanceof GovernancePrincipal governancePrincipal ? governancePrincipal : null;
    }

    /** initialize 握手观测上报（固定 initialize 方法名，口径承接旧 sse/connect 上报） */
    private void reportConnect(String traceId, String gatewayId, String status,
            int costMs, String errorMessage) {
        observabilityHelper.reportToolCall(traceId, gatewayId, "initialize", status, costMs, errorMessage);
        observabilityHelper.reportAgentDecision(traceId, traceId, null, gatewayId,
                "initialize", "DIRECT_ANSWER", "general_chat", null, null, null,
                0, 0, status, costMs, null, errorMessage);
    }

    private void reportAndWriteToolCallFailure(HttpServletRequest request, HttpServletResponse response,
            String gatewayId, JsonNode root, int jsonRpcCode, String message) throws IOException {
        String sessionId = request.getHeader(SESSION_HEADER);
        observabilityHelper.reportToolCall(sessionId, gatewayId, "tools/call", "FAIL", 0, message);
        observabilityHelper.reportAgentDecision(sessionId, sessionId, null, gatewayId,
                "tools/call", "TOOL_CALL", "tool_invocation", null, null, null,
                1, 0, "FAIL", 0, null, message);
        recordUsage(request, gatewayId, toolNameOf(root), "FAIL", 0);
        writeJsonRpcError(response, HttpServletResponse.SC_OK, jsonRpcCode, message, idOf(root));
    }

    /** 用量账本落账（工单 0046：tools/list、initialize、未知工具失败；已知工具在 registry wrapper 内落账） */
    private void recordUsage(HttpServletRequest request, String gatewayId, String toolOrModel,
            String status, int costMs) {
        if (usageLedgerService == null) {
            return;
        }
        try {
            GovernancePrincipal principal = principalOf(request);
            usageLedgerService.record(UsageRecordVO.builder()
                    .virtualKeyId(principal == null ? null : principal.getVirtualKeyId())
                    .apiKeyHash(principal == null ? null : principal.getApiKeyHash())
                    .gatewayId(gatewayId)
                    .trafficType("MCP")
                    .toolOrModel(toolOrModel)
                    .status(status)
                    .durationMs(costMs)
                    .clientIp(principal == null ? null : principal.getClientIp())
                    .sessionId(request.getHeader(SESSION_HEADER))
                    .build());
        } catch (Exception e) {
            log.warn("用量落账提交失败 gateway={} tool={}：{}", gatewayId, toolOrModel, e.getMessage());
        }
    }

    private void writeSessionNotFound(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String sessionId = request.getHeader(SESSION_HEADER);
        writeJsonRpcError(response, HttpServletResponse.SC_NOT_FOUND,
                McpErrorCodes.INTERNAL_ERROR, "Session not found or expired: " + sessionId, null);
    }

    private void writeJsonRpcError(HttpServletResponse response, int httpStatus, int jsonRpcCode,
            String message, JsonNode id) throws IOException {
        response.setStatus(httpStatus);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ObjectNode body = objectMapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        if (id != null && !id.isNull()) {
            body.set("id", id);
        } else {
            body.putNull("id");
        }
        ObjectNode error = body.putObject("error");
        error.put("code", jsonRpcCode);
        error.put("message", message == null ? "" : message);
        response.getWriter().write(objectMapper.writeValueAsString(body));
        response.getWriter().flush();
    }

    /** 读取请求体（超过上限返回 null）；包装后供委派重放 */
    private byte[] readBody(HttpServletRequest request) throws IOException {
        try (ServletInputStream inputStream = request.getInputStream()) {
            byte[] bytes = inputStream.readNBytes(MAX_MESSAGE_BODY_LENGTH + 1);
            if (bytes.length > MAX_MESSAGE_BODY_LENGTH) {
                return null;
            }
            return bytes;
        }
    }

    /** 解析并校验消息体；非法时写 400 并返回 null */
    private JsonNode parseAndValidate(String messageBody, HttpServletResponse response) throws IOException {
        if (StringUtils.isBlank(messageBody)) {
            writeJsonRpcError(response, HttpServletResponse.SC_BAD_REQUEST,
                    McpErrorCodes.INVALID_REQUEST, "messageBody不能为空", null);
            return null;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(messageBody);
        } catch (Exception e) {
            writeJsonRpcError(response, HttpServletResponse.SC_BAD_REQUEST,
                    McpErrorCodes.INVALID_REQUEST, "messageBody不是合法JSON", null);
            return null;
        }
        JsonNode methodNode = root.get("method");
        if (methodNode != null && !methodNode.isNull()) {
            String method = methodNode.asText();
            if (StringUtils.isBlank(method) || !MCP_METHOD_PATTERN.matcher(method).matches()) {
                writeJsonRpcError(response, HttpServletResponse.SC_BAD_REQUEST,
                        McpErrorCodes.INVALID_REQUEST, "method格式非法", null);
                return null;
            }
            if ("tools/call".equals(method)) {
                JsonNode toolNameNode = root.path("params").path("name");
                if (!toolNameNode.isTextual() || !MCP_METHOD_PATTERN.matcher(toolNameNode.asText()).matches()) {
                    writeJsonRpcError(response, HttpServletResponse.SC_BAD_REQUEST,
                            McpErrorCodes.INVALID_REQUEST, "toolName格式非法", null);
                    return null;
                }
            }
        }
        return root;
    }

    // ---------------------------------------------------------------------
    // Servlet 包装类
    // ---------------------------------------------------------------------

    /** 缓存请求体的重放包装（委派官方传输前预读消息体后重放） */
    static final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyHttpServletRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream buffer = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return buffer.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    // 同步读取，无需异步监听
                }

                @Override
                public int read() {
                    return buffer.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }

    /** 观察响应状态码的包装（委派回包 404 时清理陈旧会话条目） */
    static class StatusObservingResponseWrapper extends HttpServletResponseWrapper {

        private int status = HttpServletResponse.SC_OK;

        StatusObservingResponseWrapper(HttpServletResponse response) {
            super(response);
        }

        int status() {
            return status;
        }

        @Override
        public void setStatus(int statusCode) {
            this.status = statusCode;
            super.setStatus(statusCode);
        }

        @Override
        public void sendError(int statusCode, String message) throws IOException {
            this.status = statusCode;
            super.sendError(statusCode, message);
        }
    }

    /** 捕获 Mcp-Session-Id 响应头的包装（initialize 建会话即时登记回调） */
    static final class SessionCapturingResponseWrapper extends StatusObservingResponseWrapper {

        private final java.util.function.Consumer<String> onSessionId;

        private String capturedSessionId;

        SessionCapturingResponseWrapper(HttpServletResponse response, java.util.function.Consumer<String> onSessionId) {
            super(response);
            this.onSessionId = onSessionId;
        }

        String capturedSessionId() {
            return capturedSessionId;
        }

        @Override
        public void setHeader(String name, String value) {
            if (SESSION_HEADER.equalsIgnoreCase(name) && value != null && !value.isBlank()) {
                this.capturedSessionId = value;
                if (onSessionId != null) {
                    onSessionId.accept(value);
                }
            }
            super.setHeader(name, value);
        }
    }
}

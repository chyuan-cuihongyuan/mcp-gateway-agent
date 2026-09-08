package cn.chyuan.ai.infrastructure.gateway.streamable;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import cn.chyuan.ai.domain.session.adapter.repository.ISessionRepository;
import cn.chyuan.ai.domain.session.model.valobj.McpSchemaVO;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpGatewayConfigVO;
import cn.chyuan.ai.domain.session.service.tool.IMcpToolCatalogService;
import cn.chyuan.ai.domain.session.service.tool.IMcpToolInvocationService;
import cn.chyuan.ai.domain.usage.model.valobj.UsageRecordVO;
import cn.chyuan.ai.domain.usage.service.IUsageLedgerService;
import cn.chyuan.ai.infrastructure.utils.ObservabilityHelper;
import cn.chyuan.ai.infrastructure.utils.TraceContext;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网关级官方 MCP 服务器注册表（工单 0020）
 *
 * <p>per-gateway 惰性构建官方 {@link McpSyncServer}（MCP Java SDK 2.0 streamable HTTP 传输）：
 * serverInfo 取网关配置、工具规格由工具目录服务构建（VO→SDK 转换）、
 * contextExtractor 把统一认证过滤器产出的主体注入 transport context，
 * 供工具 wrapper 在 tools/call 生效点做 CEL 拦截与观测上报。
 *
 * <p>工具规格按 TTL 活体刷新（addTool/removeTool + notifyToolsListChanged），
 * 对齐工具配置缓存的新鲜度；进程关闭时统一 closeGracefully。
 *
 * @author chyuan
 */
@Slf4j
@Component
public class GatewayMcpServerRegistry {

    /** transport context 携带治理主体的键 */
    public static final String PRINCIPAL_CONTEXT_KEY = "governancePrincipal";

    @Resource
    private ISessionRepository sessionRepository;

    @Resource
    private IMcpToolCatalogService toolCatalogService;

    @Resource
    private IMcpToolInvocationService toolInvocationService;

    @Resource
    private ObservabilityHelper observabilityHelper;

    @Resource
    private IUsageLedgerService usageLedgerService;

    /** 官方服务器请求处理超时 */
    @Value("${mcp.server.request-timeout-ms:60000}")
    private long requestTimeoutMs;

    /** 工具规格刷新周期（对齐 mcp.cache.tool-config.ttl-minutes 的新鲜度） */
    @Value("${mcp.server.tool-spec-refresh-seconds:300}")
    private long toolSpecRefreshSeconds;

    private final ConcurrentHashMap<String, GatewayServerEntry> servers = new ConcurrentHashMap<>();

    /** 取网关的官方传输提供器（惰性构建 + 过期活体刷新）；网关不存在抛 NOT_FOUND */
    public HttpServletStreamableServerTransportProvider transportOf(String gatewayId) {
        GatewayServerEntry entry = servers.computeIfAbsent(gatewayId, this::buildEntry);
        if (entry.isStale(toolSpecRefreshSeconds)) {
            refreshToolSpecs(gatewayId, entry);
        }
        return entry.transport();
    }

    /** 立即失效网关条目（下一次访问重建；会话随 closeGracefully 终止）。admin 保存/删除网关配置的接线点（工单 0021 接入） */
    public void evict(String gatewayId) {
        GatewayServerEntry entry = servers.remove(gatewayId);
        if (entry != null) {
            closeQuietly(gatewayId, entry);
        }
    }

    /**
     * 标记网关条目过期（下一次访问活体刷新工具规格，会话保活）。
     * admin 工具配置/外部挂接变更、上游挂接工具漂移通知走此口径——避免误杀在途会话。
     */
    public void requestRefresh(String gatewayId) {
        GatewayServerEntry entry = servers.get(gatewayId);
        if (entry != null) {
            entry.markRefreshNeeded();
        }
    }

    @PreDestroy
    public void shutdown() {
        servers.forEach((gatewayId, entry) -> closeQuietly(gatewayId, entry));
        servers.clear();
    }

    private GatewayServerEntry buildEntry(String gatewayId) {
        McpGatewayConfigVO gatewayConfig = sessionRepository.queryMcpGatewayConfigByGatewayId(gatewayId);
        if (gatewayConfig == null) {
            throw new AppException(ResponseCode.METHOD_NOT_FOUND.getCode(), "网关配置不存在: " + gatewayId);
        }

        HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider
                .builder()
                .mcpEndpoint(endpointOf(gatewayId))
                .contextExtractor(this::extractTransportContext)
                .build();

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo(gatewayConfig.getGatewayName(),
                        gatewayConfig.getVersion() == null ? "1.0.0" : gatewayConfig.getVersion())
                .instructions(gatewayConfig.getGatewayDesc())
                .capabilities(new McpSchema.ServerCapabilities(null, null, null, null, null,
                        new McpSchema.ServerCapabilities.ToolCapabilities(true)))
                .requestTimeout(Duration.ofMillis(requestTimeoutMs))
                .tools(buildToolSpecs(gatewayId))
                .build();

        log.info("网关官方 MCP 服务器已构建: gatewayId={}, endpoint={}, tools={}",
                gatewayId, endpointOf(gatewayId), server.listTools().size());
        return new GatewayServerEntry(transport, server);
    }

    /** 认证过滤器写入的请求属性 → transport context（工具 wrapper 经 exchange 取回，异步执行不依赖 ThreadLocal） */
    private McpTransportContext extractTransportContext(HttpServletRequest request) {
        Object principal = request.getAttribute(GovernancePrincipal.REQUEST_ATTR);
        if (principal instanceof GovernancePrincipal governancePrincipal) {
            Map<String, Object> context = new HashMap<>();
            context.put(PRINCIPAL_CONTEXT_KEY, governancePrincipal);
            return McpTransportContext.create(context);
        }
        return McpTransportContext.EMPTY;
    }

    private List<McpServerFeatures.SyncToolSpecification> buildToolSpecs(String gatewayId) {
        // 规格构建不做 CEL 过滤（principal 为空）：全部注册，运行期由 tools/list 拦截与 tools/call wrapper 双生效点控制
        List<McpSchemaVO.Tool> catalog = toolCatalogService.visibleTools(gatewayId, null, "tools/call");
        return catalog.stream()
                .map(voTool -> McpServerFeatures.SyncToolSpecification.builder()
                        .tool(toSdkTool(voTool))
                        .callHandler((exchange, request) -> invokeTool(gatewayId, exchange, request))
                        .build())
                .toList();
    }

    /** VO 工具 → SDK 工具（inputSchema 转通用 Map，保持字段顺序与空值省略） */
    private McpSchema.Tool toSdkTool(McpSchemaVO.Tool voTool) {
        Map<String, Object> inputSchema = new LinkedHashMap<>();
        McpSchemaVO.JsonSchema voSchema = voTool.inputSchema();
        if (voSchema != null) {
            inputSchema.put("type", voSchema.type() == null ? "object" : voSchema.type());
            if (voSchema.properties() != null && !voSchema.properties().isEmpty()) {
                inputSchema.put("properties", voSchema.properties());
            }
            if (voSchema.required() != null && !voSchema.required().isEmpty()) {
                inputSchema.put("required", voSchema.required());
            }
            if (voSchema.additionalProperties() != null) {
                inputSchema.put("additionalProperties", voSchema.additionalProperties());
            }
            if (voSchema.defs() != null && !voSchema.defs().isEmpty()) {
                inputSchema.put("$defs", voSchema.defs());
            }
            if (voSchema.definitions() != null && !voSchema.definitions().isEmpty()) {
                inputSchema.put("definitions", voSchema.definitions());
            }
        } else {
            inputSchema.put("type", "object");
            inputSchema.put("properties", Map.of());
        }
        return new McpSchema.Tool(voTool.name(), null, voTool.description(), inputSchema, null, null, null);
    }

    /** tools/call 生效点：CEL 拦截 + 协议映射调用 + 观测上报（traceId=会话 id，X-Trace-Id 注入下游） */
    private McpSchema.CallToolResult invokeTool(String gatewayId, McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        String sessionId = exchange.sessionId();
        String toolName = request.name();
        GovernancePrincipal principal = principalOf(exchange);
        long start = System.currentTimeMillis();

        TraceContext.setTraceId(sessionId);
        try {
            Object payload = toolInvocationService.invoke(gatewayId, toolName, request.arguments(), principal);
            int cost = (int) (System.currentTimeMillis() - start);
            reportInvocation(sessionId, gatewayId, toolName, "SUCCESS", cost, null);
            recordUsage(sessionId, gatewayId, toolName, principal, "SUCCESS", cost);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(payload == null ? "" : String.valueOf(payload))),
                    false, null, null);
        } catch (AppException e) {
            int cost = (int) (System.currentTimeMillis() - start);
            reportInvocation(sessionId, gatewayId, toolName, "FAIL", cost, e.getInfo());
            recordUsage(sessionId, gatewayId, toolName, principal, "FAIL", cost);
            throw new McpError(new McpSchema.JSONRPCResponse.JSONRPCError(
                    jsonRpcErrorCode(e), e.getMessage(), null));
        } catch (Exception e) {
            int cost = (int) (System.currentTimeMillis() - start);
            reportInvocation(sessionId, gatewayId, toolName, "FAIL", cost, e.getMessage());
            recordUsage(sessionId, gatewayId, toolName, principal, "FAIL", cost);
            throw new McpError(new McpSchema.JSONRPCResponse.JSONRPCError(
                    McpErrorCodes.INTERNAL_ERROR, "内部错误: " + e.getMessage(), null));
        } finally {
            TraceContext.clear();
        }
    }

    /** 用量账本落账（工单 0046：tools/call 全量含 CEL 拒绝；异步不阻断） */
    private void recordUsage(String sessionId, String gatewayId, String toolName,
            GovernancePrincipal principal, String status, int costMs) {
        try {
            usageLedgerService.record(UsageRecordVO.builder()
                    .virtualKeyId(principal == null ? null : principal.getVirtualKeyId())
                    .apiKeyHash(principal == null ? null : principal.getApiKeyHash())
                    .gatewayId(gatewayId)
                    .trafficType("MCP")
                    .toolOrModel(toolName)
                    .status(status)
                    .durationMs(costMs)
                    .clientIp(principal == null ? null : principal.getClientIp())
                    .sessionId(sessionId)
                    .build());
        } catch (Exception e) {
            log.warn("用量落账提交失败 tool={}：{}", toolName, e.getMessage());
        }
    }

    /** 工具调用观测上报（成功/失败同构；口径承接旧 SSE handleMessage 上报） */
    private void reportInvocation(String sessionId, String gatewayId, String toolName, String status,
            int costMs, String errorMessage) {
        observabilityHelper.reportToolCall(sessionId, gatewayId, toolName, status, costMs, errorMessage);
        observabilityHelper.reportAgentDecision(sessionId, sessionId, null, gatewayId,
                toolName, "TOOL_CALL", "tool_invocation", toolName, null, null,
                1, 0, status, costMs, null, errorMessage);
    }

    private GovernancePrincipal principalOf(McpSyncServerExchange exchange) {
        McpTransportContext context = exchange.transportContext();
        if (context == null) {
            return null;
        }
        Object principal = context.get(PRINCIPAL_CONTEXT_KEY);
        return principal instanceof GovernancePrincipal governancePrincipal ? governancePrincipal : null;
    }

    /** AppException 携带 JSON-RPC 数值码时透传（-32006 无权限 / -32003 不存在），否则按非法参数 */
    private static int jsonRpcErrorCode(AppException e) {
        try {
            return Integer.parseInt(e.getCode());
        } catch (NumberFormatException ignore) {
            return McpErrorCodes.INVALID_PARAMS;
        }
    }

    /** 活体刷新工具规格：diff 后增删并广播 listChanged（保会话存活） */
    private void refreshToolSpecs(String gatewayId, GatewayServerEntry entry) {
        synchronized (entry) {
            if (!entry.isStale(toolSpecRefreshSeconds)) {
                return;
            }
            try {
                List<McpServerFeatures.SyncToolSpecification> desired = buildToolSpecs(gatewayId);
                Set<String> desiredNames = new LinkedHashSet<>();
                desired.forEach(spec -> desiredNames.add(spec.tool().name()));

                List<String> currentNames = entry.server().listTools().stream().map(McpSchema.Tool::name).toList();
                List<String> removed = new ArrayList<>();
                for (String name : currentNames) {
                    if (!desiredNames.contains(name)) {
                        entry.server().removeTool(name);
                        removed.add(name);
                    }
                }
                Set<String> currentNameSet = Set.copyOf(currentNames);
                List<String> added = new ArrayList<>();
                for (McpServerFeatures.SyncToolSpecification spec : desired) {
                    if (!currentNameSet.contains(spec.tool().name())) {
                        entry.server().addTool(spec);
                        added.add(spec.tool().name());
                    }
                }
                if (!removed.isEmpty() || !added.isEmpty()) {
                    entry.server().notifyToolsListChanged();
                    log.info("网关工具规格已刷新: gatewayId={}, added={}, removed={}", gatewayId, added, removed);
                }
            } catch (Exception e) {
                log.warn("网关工具规格刷新失败（保留现规格）: gatewayId={}, reason={}", gatewayId, e.getMessage());
            } finally {
                entry.markRefreshed();
            }
        }
    }

    private void closeQuietly(String gatewayId, GatewayServerEntry entry) {
        try {
            entry.server().closeGracefully();
        } catch (Exception e) {
            log.warn("关闭网关官方 MCP 服务器失败: gatewayId={}, reason={}", gatewayId, e.getMessage());
        }
    }

    private static String endpointOf(String gatewayId) {
        return "/api-gateway/" + gatewayId + "/mcp";
    }

    /** 网关服务器条目（transport + server + 刷新时间戳） */
    static final class GatewayServerEntry {

        private final HttpServletStreamableServerTransportProvider transport;
        private final McpSyncServer server;
        private volatile long refreshedAt = System.currentTimeMillis();

        GatewayServerEntry(HttpServletStreamableServerTransportProvider transport, McpSyncServer server) {
            this.transport = transport;
            this.server = server;
        }

        HttpServletStreamableServerTransportProvider transport() {
            return transport;
        }

        McpSyncServer server() {
            return server;
        }

        boolean isStale(long refreshSeconds) {
            return refreshedAt <= 0
                    || System.currentTimeMillis() - refreshedAt >= refreshSeconds * 1000;
        }

        void markRefreshed() {
            refreshedAt = System.currentTimeMillis();
        }

        void markRefreshNeeded() {
            refreshedAt = 0;
        }
    }
}

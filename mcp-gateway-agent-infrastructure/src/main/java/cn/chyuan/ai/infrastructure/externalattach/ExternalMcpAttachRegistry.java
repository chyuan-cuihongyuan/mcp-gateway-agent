package cn.chyuan.ai.infrastructure.externalattach;

import cn.chyuan.ai.domain.externalattach.adapter.port.IExternalMcpAttachPort;
import cn.chyuan.ai.domain.externalattach.adapter.repository.IExternalAttachRepository;
import cn.chyuan.ai.domain.externalattach.model.valobj.ExternalAttachVO;
import cn.chyuan.ai.domain.governance.adapter.IGovernanceEventPublisher;
import cn.chyuan.ai.domain.session.model.valobj.McpSchemaVO;
import cn.chyuan.ai.infrastructure.gateway.streamable.GatewayMcpServerRegistry;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 外部 MCP 挂接客户端注册表（工单 0021）
 *
 * <p>per-attach 惰性构建官方 {@link McpSyncClient}（streamable HTTP / stdio），
 * 上游工具并入网关目录（命名 {@code attachName_toolName}）、tools/call 透传。
 *
 * <p>行为约定：
 * <ul>
 * <li>连接失败可观测：入口状态机 UNKNOWN→CONNECTED/FAILED，回写库表并在 admin API 呈现；
 *     FAILED 挂接的工具从目录跳过，按 {@code failed-retry-seconds} 周期重试。</li>
 * <li>漂移检测：{@code toolsChangeConsumer} 收上游 listChanged 通知后失效工具缓存并
 *     {@link GatewayMcpServerRegistry#requestRefresh} 保会话刷新网关侧规格
 *     （与 0020 的 TTL 活体 diff 同链路）。</li>
 * <li>配置缓存：网关→挂接配置短 TTL 缓存，admin 变更后 {@link #evictAttach} 即时失效。</li>
 * </ul>
 *
 * @author chyuan
 */
@Slf4j
@Component
public class ExternalMcpAttachRegistry implements IExternalMcpAttachPort {

    private static final String STATUS_UNKNOWN = "UNKNOWN";
    private static final String STATUS_CONNECTED = "CONNECTED";
    private static final String STATUS_FAILED = "FAILED";

    @Resource
    private IExternalAttachRepository attachRepository;

    /** 被动熔断事件（工单 0059，0051 webhook 订阅口径） */
    public static final String EVENT_CIRCUIT_OPEN = "CIRCUIT_OPEN";

    /** 被动熔断阈值（0=关闭；单类失败计数达阈值即自动禁用+冷却，探测成功恢复） */
    @Value("${mcp.external.attach.circuit-threshold:5}")
    private int circuitThreshold;

    /** 自动禁用冷却秒（与巡检共用配置口径） */
    @Value("${mcp.external.attach.cooldown-seconds:120}")
    private long cooldownSeconds;

    @Resource
    private IGovernanceEventPublisher eventPublisher;

    /** 惰性解析：与网关服务器注册表存在 catalog→attach→registry 环，ObjectProvider 打断构造期循环 */
    @Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<GatewayMcpServerRegistry> gatewayServerRegistryProvider;

    /** 挂接配置缓存 TTL（admin 变更即时失效，TTL 兜底） */
    @Value("${mcp.external.attach.config-cache-seconds:60}")
    private long configCacheSeconds;

    /** 连接失败挂接的重试周期 */
    @Value("${mcp.external.attach.failed-retry-seconds:60}")
    private long failedRetrySeconds;

    /** attachId → 客户端条目 */
    private final ConcurrentHashMap<Long, AttachClientEntry> clients = new ConcurrentHashMap<>();

    /** gatewayId → 挂接配置缓存（短 TTL） */
    private final ConcurrentHashMap<String, ConfigCacheEntry> configCache = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // 目录并入（tools/list）与路由判断（tools/call）
    // ------------------------------------------------------------------

    @Override
    public List<McpSchemaVO.Tool> listAttachedTools(String gatewayId) {
        List<McpSchemaVO.Tool> tools = new ArrayList<>();
        for (ExternalAttachVO config : enabledAttaches(gatewayId)) {
            AttachClientEntry entry = entryOf(config);
            if (entry == null || !STATUS_CONNECTED.equals(entry.connectStatus.get())) {
                continue;
            }
            try {
                tools.addAll(cachedTools(entry).stream().map(AttachedToolInfo::tool).toList());
            } catch (Exception e) {
                log.warn("挂接工具清单获取失败（跳过该挂接）: gatewayId={}, attach={}, reason={}",
                        gatewayId, config.getAttachName(), e.getMessage());
            }
        }
        return tools;
    }

    @Override
    public boolean isExternalTool(String gatewayId, String prefixedToolName) {
        if (prefixedToolName == null || prefixedToolName.isBlank()) {
            return false;
        }
        for (ExternalAttachVO config : enabledAttaches(gatewayId)) {
            if (!prefixedToolName.startsWith(config.getAttachName() + "_")) {
                continue;
            }
            AttachClientEntry entry = entryOf(config);
            if (entry != null && STATUS_CONNECTED.equals(entry.connectStatus.get())) {
                return cachedTools(entry).stream().anyMatch(t -> t.prefixedName.equals(prefixedToolName));
            }
        }
        return false;
    }

    @Override
    public ExternalCallResult callExternalTool(String gatewayId, String prefixedToolName,
            Map<String, Object> arguments) {
        for (ExternalAttachVO config : enabledAttaches(gatewayId)) {
            if (!prefixedToolName.startsWith(config.getAttachName() + "_")) {
                continue;
            }
            AttachClientEntry entry = entryOf(config);
            if (entry == null || !STATUS_CONNECTED.equals(entry.connectStatus.get())) {
                throw new IllegalStateException("挂接不可用: " + config.getAttachName());
            }
            String rawName = prefixedToolName.substring(config.getAttachName().length() + 1);
            try {
                McpSchema.CallToolResult result = entry.client().callTool(
                        new McpSchema.CallToolRequest(rawName, arguments));
                // 成功清零全部失败计数（Kong passive health 口径，工单 0059）
                if (circuitThreshold > 0) {
                    clearCircuitCountersQuietly(config);
                }
                return new ExternalCallResult(Boolean.TRUE.equals(result.isError()), renderContent(result));
            } catch (RuntimeException e) {
                // 失败分类计数：超时 / 连接 / 其余（上游错误）；达阈值自动禁用+冷却（半开恢复归巡检）
                recordCircuitFailureQuietly(config, classifyFailure(e));
                throw e;
            }
        }
        throw new IllegalStateException("工具不属于任何挂接: " + prefixedToolName);
    }

    /** 失败三分类（工单 0059） */
    static String classifyFailure(Throwable failure) {
        String text = failure == null ? "" : String.valueOf(failure.getMessage());
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String name = cause.getClass().getSimpleName();
            if (name.contains("Timeout") || name.contains("timeout")) {
                return "fail_timeout";
            }
            if (name.contains("Connect") || name.contains("Unresolved")) {
                return "fail_connect";
            }
        }
        if (text.contains("timed out") || text.contains("timeout") || text.contains("超时")) {
            return "fail_timeout";
        }
        if (text.contains("Connection refused") || text.contains("connect") || text.contains("连接")) {
            return "fail_connect";
        }
        return "fail_http";
    }

    private void recordCircuitFailureQuietly(ExternalAttachVO config, String column) {
        if (circuitThreshold <= 0 || column == null) {
            return;
        }
        try {
            attachRepository.incrementChannelFailure(config.getId(), column);
            // 达阈值即自动禁用 + 冷却（计数清零由 updateChannelStatus 一并处理）
            ExternalAttachVO latest = attachRepository.findById(config.getId());
            int counted = switch (column) {
                case "fail_connect" -> latest == null || latest.getFailConnect() == null ? 0 : latest.getFailConnect();
                case "fail_timeout" -> latest == null || latest.getFailTimeout() == null ? 0 : latest.getFailTimeout();
                default -> latest == null || latest.getFailHttp() == null ? 0 : latest.getFailHttp();
            };
            if (counted >= circuitThreshold && latest != null
                    && latest.getStatus() != null && latest.getStatus() == ExternalAttachVO.STATUS_ENABLED) {
                Date cooldownUntil = new Date(System.currentTimeMillis() + cooldownSeconds * 1000);
                attachRepository.updateChannelStatus(config.getId(), ExternalAttachVO.STATUS_AUTO_DISABLED, cooldownUntil);
                publishCircuitEvent(config, column, counted, cooldownUntil);
                log.warn("被动熔断打开: gateway={} attach={} {}={} 阈值{} 冷却至{}",
                        config.getGatewayId(), config.getAttachName(), column, counted, circuitThreshold, cooldownUntil);
            }
        } catch (Exception e) {
            log.warn("熔断计数失败（不影响调用主链）attach={}：{}", config.getAttachName(), e.getMessage());
        }
    }

    private void clearCircuitCountersQuietly(ExternalAttachVO config) {
        // 去热化：缓存计数全零（常态）即跳过写库，避免成功路径每调用一次写一行
        boolean hasCounters = (config.getFailConnect() != null && config.getFailConnect() > 0)
                || (config.getFailTimeout() != null && config.getFailTimeout() > 0)
                || (config.getFailHttp() != null && config.getFailHttp() > 0);
        if (!hasCounters) {
            return;
        }
        try {
            // 复用状态迁移语句清零（状态保持启用、冷却清空）
            attachRepository.updateChannelStatus(config.getId(), ExternalAttachVO.STATUS_ENABLED, null);
        } catch (Exception e) {
            log.debug("熔断计数清零失败 attach={}：{}", config.getAttachName(), e.getMessage());
        }
    }

    private void publishCircuitEvent(ExternalAttachVO config, String column, int counted, Date cooldownUntil) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("gatewayId", config.getGatewayId());
            payload.put("attachName", config.getAttachName());
            payload.put("attachId", config.getId());
            payload.put("failureType", column);
            payload.put("failures", counted);
            payload.put("cooldownUntil", cooldownUntil.getTime());
            eventPublisher.publish(EVENT_CIRCUIT_OPEN, payload);
        } catch (Exception e) {
            log.debug("熔断事件发布失败：{}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 探活与运行期状态（admin 可观测）
    // ------------------------------------------------------------------

    @Override
    public ConnectState probeConnect(ExternalAttachVO attach) {
        McpSyncClient probe = null;
        try {
            probe = buildClient(attach, null);
            McpSchema.InitializeResult init = probe.initialize();
            int toolCount = probe.listTools().tools().size();
            return new ConnectState(true, toolCount, null);
        } catch (Exception e) {
            return new ConnectState(false, 0, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            if (probe != null) {
                try {
                    probe.closeGracefully();
                } catch (Exception ignore) {
                    // 探活客户端关闭失败无碍
                }
            }
        }
    }

    @Override
    public List<AttachRuntimeStatus> runtimeStatuses(String gatewayId) {
        List<AttachRuntimeStatus> statuses = new ArrayList<>();
        for (ExternalAttachVO config : attachConfigs(gatewayId)) {
            AttachClientEntry entry = clients.get(config.getId());
            if (entry == null) {
                statuses.add(new AttachRuntimeStatus(config.getId(), config.getAttachName(),
                        STATUS_UNKNOWN, null, null, 0));
                continue;
            }
            StatusSnapshot snapshot = entry.snapshot();
            statuses.add(new AttachRuntimeStatus(config.getId(), config.getAttachName(),
                    snapshot.status(), snapshot.error(), snapshot.time(), snapshot.toolCount()));
        }
        return statuses;
    }

    // ------------------------------------------------------------------
    // 失效（admin 变更 / 网关删除）
    // ------------------------------------------------------------------

    @Override
    public void evictAttach(Long attachId, String gatewayId) {
        AttachClientEntry entry = clients.remove(attachId);
        if (entry != null) {
            closeQuietly(entry);
        }
        invalidateConfigs(gatewayId);
        requestGatewayRefresh(gatewayId);
        log.info("外部挂接客户端已失效: attachId={}, gatewayId={}", attachId, gatewayId);
    }

    @Override
    public void evictGateway(String gatewayId) {
        invalidateConfigs(gatewayId);
        for (ExternalAttachVO config : attachRepository.findByGatewayId(gatewayId)) {
            AttachClientEntry entry = clients.remove(config.getId());
            if (entry != null) {
                closeQuietly(entry);
            }
        }
        log.info("网关外部挂接已整体失效: gatewayId={}", gatewayId);
    }

    @PreDestroy
    public void shutdown() {
        clients.forEach((id, entry) -> closeQuietly(entry));
        clients.clear();
    }

    // ------------------------------------------------------------------
    // 内部：配置缓存 / 客户端构建 / 工具缓存
    // ------------------------------------------------------------------

    private List<ExternalAttachVO> enabledAttaches(String gatewayId) {
        return attachConfigs(gatewayId).stream()
                .filter(config -> Integer.valueOf(1).equals(config.getStatus()))
                .toList();
    }

    private List<ExternalAttachVO> attachConfigs(String gatewayId) {
        if (gatewayId == null || gatewayId.isBlank()) {
            return List.of();
        }
        ConfigCacheEntry cached = configCache.compute(gatewayId, (gid, entry) ->
                entry == null || entry.isStale(configCacheSeconds)
                        ? new ConfigCacheEntry(attachRepository.findByGatewayId(gid))
                        : entry);
        return cached.attaches();
    }

    private void invalidateConfigs(String gatewayId) {
        if (gatewayId != null) {
            configCache.remove(gatewayId);
        } else {
            configCache.clear();
        }
    }

    /** 取/建挂接客户端条目；连接失败记录可观测状态并按周期重试 */
    private AttachClientEntry entryOf(ExternalAttachVO config) {
        AttachClientEntry entry = clients.compute(config.getId(), (id, existing) -> {
            if (existing != null && existing.matches(config)) {
                return existing;
            }
            if (existing != null) {
                closeQuietly(existing);
            }
            return buildEntry(config);
        });
        // FAILED 条目按周期重试：超时后重建
        if (STATUS_FAILED.equals(entry.connectStatus.get())
                && System.currentTimeMillis() - entry.statusChangedAt.get() >= failedRetrySeconds * 1000) {
            synchronized (entry) {
                if (STATUS_FAILED.equals(entry.connectStatus.get())
                        && System.currentTimeMillis() - entry.statusChangedAt.get() >= failedRetrySeconds * 1000) {
                    clients.put(config.getId(), buildEntry(config));
                    entry = clients.get(config.getId());
                }
            }
        }
        return entry;
    }

    private AttachClientEntry buildEntry(ExternalAttachVO config) {
        AttachClientEntry entry = new AttachClientEntry(config);
        try {
            McpSyncClient client = buildClient(config, entry);
            McpSchema.InitializeResult init = client.initialize();
            entry.client = client;
            entry.connectStatus.set(STATUS_CONNECTED);
            entry.connectError = null;
            entry.statusChangedAt.set(System.currentTimeMillis());
            entry.connectTime = new Date();
            persistConnectStatus(config, STATUS_CONNECTED, null);
            log.info("外部挂接已连接: gatewayId={}, attach={}, server={}/{}",
                    config.getGatewayId(), config.getAttachName(),
                    init.serverInfo().name(), init.serverInfo().version());
        } catch (Exception e) {
            entry.connectStatus.set(STATUS_FAILED);
            entry.connectError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            entry.statusChangedAt.set(System.currentTimeMillis());
            entry.connectTime = new Date();
            persistConnectStatus(config, STATUS_FAILED, entry.connectError);
            log.warn("外部挂接连接失败: gatewayId={}, attach={}, reason={}",
                    config.getGatewayId(), config.getAttachName(), entry.connectError);
        }
        return entry;
    }

    /** 按传输类型构建官方客户端；changeListener 非空时注册上游 listChanged 漂移消费 */
    private McpSyncClient buildClient(ExternalAttachVO config, AttachClientEntry listener) {
        McpClient.SyncSpec spec;
        if (ExternalAttachVO.TRANSPORT_STREAMABLE_HTTP.equals(config.getTransportType())) {
            spec = McpClient.sync(streamableTransport(config));
        } else {
            spec = McpClient.sync(stdioTransport(config));
        }
        spec.requestTimeout(Duration.ofMillis(config.getRequestTimeoutMs() == null
                ? 30_000 : config.getRequestTimeoutMs()))
                .clientInfo(new McpSchema.Implementation("mcp-gateway-attach-" + config.getAttachName(), "1.0.0"));
        if (listener != null) {
            spec.toolsChangeConsumer(tools -> {
                log.info("外部挂接上游工具清单变更: gatewayId={}, attach={}, 上游工具数={}",
                        config.getGatewayId(), config.getAttachName(), tools.size());
                listener.toolsCache = null;
                requestGatewayRefresh(config.getGatewayId());
            });
        }
        return spec.build();
    }

    private HttpClientStreamableHttpTransport streamableTransport(ExternalAttachVO config) {
        URI uri = URI.create(config.getEndpoint());
        String baseUri = uri.getScheme() + "://" + uri.getRawAuthority();
        String endpoint = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/mcp" : uri.getRawPath()
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());

        HttpClientStreamableHttpTransport.Builder builder = HttpClientStreamableHttpTransport
                .builder(baseUri)
                .endpoint(endpoint);
        if (StringUtils.isNotBlank(config.getApiKey())) {
            builder.httpRequestCustomizer((requestBuilder, method, reqUri, body, context) ->
                    requestBuilder.header("Authorization", "Bearer " + config.getApiKey()));
        }
        return builder.build();
    }

    private StdioClientTransport stdioTransport(ExternalAttachVO config) {
        List<String> args = StringUtils.isBlank(config.getArgs())
                ? List.of()
                : com.alibaba.fastjson.JSON.parseArray(config.getArgs()).toJavaList(String.class);
        Map<String, String> env = StringUtils.isBlank(config.getEnv())
                ? Map.of()
                : com.alibaba.fastjson.JSON.parseObject(config.getEnv(),
                        new com.alibaba.fastjson.TypeReference<Map<String, String>>() {
                        });
        ServerParameters params = ServerParameters.builder(config.getCommand())
                .args(args)
                .env(env)
                .build();
        return new StdioClientTransport(params, new JacksonMcpJsonMapper(JsonMapper.builder().build()));
    }

    /** 工具缓存（上游 listTools 结果，前缀命名；consumer 失效后下次访问重拉） */
    private List<AttachedToolInfo> cachedTools(AttachClientEntry entry) {
        List<AttachedToolInfo> cached = entry.toolsCache;
        if (cached != null) {
            return cached;
        }
        synchronized (entry) {
            cached = entry.toolsCache;
            if (cached != null) {
                return cached;
            }
            String prefix = entry.config.getAttachName() + "_";
            List<AttachedToolInfo> tools = entry.client().listTools().tools().stream()
                    .map(tool -> new AttachedToolInfo(prefix + tool.name(), toVoTool(prefix, tool)))
                    .toList();
            entry.toolsCache = tools;
            return tools;
        }
    }

    /** SDK Tool → VO Tool（inputSchema Map → JsonSchema，保序透传） */
    private McpSchemaVO.Tool toVoTool(String prefix, McpSchema.Tool tool) {
        Map<String, Object> raw = tool.inputSchema() == null ? Map.of() : tool.inputSchema();
        McpSchemaVO.JsonSchema schema = new McpSchemaVO.JsonSchema(
                raw.get("type") instanceof String type ? type : "object",
                raw.get("properties") instanceof Map<?, ?> properties ? (Map<String, Object>) properties : Map.of(),
                raw.get("required") instanceof List<?> required ? (List<String>) required : null,
                raw.get("additionalProperties") instanceof Boolean additional ? additional : null,
                raw.get("$defs") instanceof Map<?, ?> defs ? (Map<String, Object>) defs : null,
                raw.get("definitions") instanceof Map<?, ?> definitions ? (Map<String, Object>) definitions : null);
        return new McpSchemaVO.Tool(prefix + tool.name(), tool.description(), schema);
    }

    /** CallToolResult 内容渲染为字符串载荷（单一文本内容取原文，其余取 JSON） */
    private String renderContent(McpSchema.CallToolResult result) {
        if (result.content() == null || result.content().isEmpty()) {
            return "";
        }
        if (result.content().size() == 1 && result.content().get(0) instanceof McpSchema.TextContent text) {
            return text.text();
        }
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent text) {
                sb.append(text.text());
            } else {
                sb.append(String.valueOf(content));
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private void persistConnectStatus(ExternalAttachVO config, String status, String error) {
        try {
            attachRepository.updateConnectStatus(config.getId(), status, error);
        } catch (Exception e) {
            // 状态回写失败不影响主链路（内存态仍可经 admin API 呈现）
            log.warn("挂接连接状态回写失败: attachId={}, reason={}", config.getId(), e.getMessage());
        }
    }

    private void closeQuietly(AttachClientEntry entry) {
        try {
            if (entry.client != null) {
                entry.client.closeGracefully();
            }
        } catch (Exception e) {
            log.warn("关闭外部挂接客户端失败: attach={}, reason={}", entry.config.getAttachName(), e.getMessage());
        }
    }

    /** 惰性请求网关服务器保会话刷新目录规格（provider 缺席时跳过，如切片上下文） */
    private void requestGatewayRefresh(String gatewayId) {
        if (gatewayId == null || gatewayServerRegistryProvider == null) {
            return;
        }
        try {
            gatewayServerRegistryProvider.getObject().requestRefresh(gatewayId);
        } catch (Exception e) {
            log.debug("请求网关目录刷新失败: gatewayId={}, reason={}", gatewayId, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 条目结构
    // ------------------------------------------------------------------

    private record AttachedToolInfo(String prefixedName, McpSchemaVO.Tool tool) {
    }

    private record StatusSnapshot(String status, String error, Date time, int toolCount) {
    }

    /** 挂接客户端条目（客户端 + 连接状态 + 工具缓存） */
    static final class AttachClientEntry {

        final ExternalAttachVO config;

        volatile McpSyncClient client;

        final java.util.concurrent.atomic.AtomicReference<String> connectStatus =
                new java.util.concurrent.atomic.AtomicReference<>(STATUS_UNKNOWN);

        final java.util.concurrent.atomic.AtomicLong statusChangedAt =
                new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());

        volatile String connectError;

        volatile Date connectTime;

        volatile List<AttachedToolInfo> toolsCache;

        AttachClientEntry(ExternalAttachVO config) {
            this.config = config;
        }

        McpSyncClient client() {
            McpSyncClient client = this.client;
            if (client == null) {
                throw new IllegalStateException("挂接未连接: " + config.getAttachName());
            }
            return client;
        }

        /** 配置指纹：核心字段变化视为需重建客户端（0047 起含鉴权类型/配置） */
        boolean matches(ExternalAttachVO latest) {
            return latest != null
                    && config.getTransportType().equals(latest.getTransportType())
                    && StringUtils.equals(config.getEndpoint(), latest.getEndpoint())
                    && StringUtils.equals(config.getApiKey(), latest.getApiKey())
                    && StringUtils.equals(config.getCommand(), latest.getCommand())
                    && StringUtils.equals(config.getArgs(), latest.getArgs())
                    && StringUtils.equals(config.getEnv(), latest.getEnv())
                    && StringUtils.equals(config.getAuthType(), latest.getAuthType())
                    && StringUtils.equals(config.getAuthConfig(), latest.getAuthConfig())
                    && java.util.Objects.equals(config.getRequestTimeoutMs(), latest.getRequestTimeoutMs());
        }

        StatusSnapshot snapshot() {
            List<AttachedToolInfo> tools = toolsCache;
            return new StatusSnapshot(connectStatus.get(), connectError, connectTime,
                    tools == null ? 0 : tools.size());
        }
    }

    /** 挂接配置缓存条目 */
    private static final class ConfigCacheEntry {

        final List<ExternalAttachVO> attaches;

        final long loadedAt = System.currentTimeMillis();

        ConfigCacheEntry(List<ExternalAttachVO> attaches) {
            this.attaches = List.copyOf(attaches);
        }

        boolean isStale(long ttlSeconds) {
            return System.currentTimeMillis() - loadedAt >= ttlSeconds * 1000;
        }

        List<ExternalAttachVO> attaches() {
            return attaches;
        }
    }
}

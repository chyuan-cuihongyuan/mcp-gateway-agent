package cn.chyuan.ai.domain.session.service.tool;

import cn.chyuan.ai.domain.externalattach.adapter.port.IExternalMcpAttachPort;
import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import cn.chyuan.ai.domain.governance.service.CelEvaluationService;
import cn.chyuan.ai.domain.governance.service.ICelEvaluationService;
import cn.chyuan.ai.domain.session.adapter.repository.ISessionRepository;
import cn.chyuan.ai.domain.session.model.valobj.McpSchemaVO;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolConfigVO;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolProtocolConfigVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * MCP 工具目录服务（工单 0020 / 0021 外部挂接并入）
 *
 * <p>schema 构建逻辑承接原 ToolsListHandler（SSE 消息树随传输下线删除），
 * CEL 过滤口径与 0018 一致：不放行的工具从清单隐藏。
 *
 * <p>0021：外部 MCP 挂接（streamable HTTP/stdio）的上游工具以
 * {@code attachName_toolName} 并入清单，CEL 以 tool.source=EXTERNAL 求值；
 * 连接失败的挂接自动跳过（可观测状态经 admin API 呈现）。
 * 协议映射工具与外部工具重名时协议映射优先。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class McpToolCatalogService implements IMcpToolCatalogService {

    /** 协议映射排序（sortOrder 空视为 0） */
    private static final Comparator<McpToolProtocolConfigVO.ProtocolMapping> BY_SORT_ORDER =
            Comparator.comparingInt(mapping -> mapping.getSortOrder() != null ? mapping.getSortOrder() : 0);

    @Resource
    private ISessionRepository repository;

    @Resource
    private ICelEvaluationService celEvaluationService;

    /** 外部挂接端口（可选注入：单测/切片可缺省，仅协议来源生效） */
    @Autowired(required = false)
    private IExternalMcpAttachPort externalMcpAttachPort;

    @Override
    public List<McpSchemaVO.Tool> visibleTools(String gatewayId, GovernancePrincipal principal, String method) {
        List<McpToolConfigVO> toolConfigs = repository.queryMcpGatewayToolConfigListByGatewayId(gatewayId);

        List<McpToolConfigVO> visible = principal == null
                ? toolConfigs
                : toolConfigs.stream()
                        .filter(tool -> celEvaluationService.isToolAllowed(principal, gatewayId, method,
                                tool.getToolName(), CelEvaluationService.TOOL_SOURCE_PROTOCOL))
                        .toList();

        List<McpSchemaVO.Tool> tools = new ArrayList<>(buildTools(visible));
        tools.addAll(externalTools(gatewayId, principal, method, tools));
        return tools;
    }

    @Override
    public boolean toolExists(String gatewayId, String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        boolean protocolExists = repository.queryMcpGatewayToolConfigListByGatewayId(gatewayId).stream()
                .anyMatch(tool -> toolName.equals(tool.getToolName()));
        if (protocolExists) {
            return true;
        }
        return externalMcpAttachPort != null && externalMcpAttachPort.isExternalTool(gatewayId, toolName);
    }

    /** 外部挂接工具（CEL 按 EXTERNAL 来源求值；重名跳过并告警——协议映射优先） */
    private List<McpSchemaVO.Tool> externalTools(String gatewayId, GovernancePrincipal principal,
            String method, List<McpSchemaVO.Tool> protocolTools) {
        if (externalMcpAttachPort == null) {
            return List.of();
        }
        List<McpSchemaVO.Tool> external;
        try {
            external = externalMcpAttachPort.listAttachedTools(gatewayId);
        } catch (Exception e) {
            log.warn("外部挂接工具清单获取失败（跳过外部来源）: gatewayId={}, reason={}", gatewayId, e.getMessage());
            return List.of();
        }
        if (external.isEmpty()) {
            return List.of();
        }
        Set<String> protocolNames = new HashSet<>();
        protocolTools.forEach(tool -> protocolNames.add(tool.name()));

        List<McpSchemaVO.Tool> visible = new ArrayList<>();
        for (McpSchemaVO.Tool tool : external) {
            if (protocolNames.contains(tool.name())) {
                log.warn("外部工具与协议映射工具重名，保留协议映射: gatewayId={}, tool={}", gatewayId, tool.name());
                continue;
            }
            if (principal == null || celEvaluationService.isToolAllowed(principal, gatewayId, method,
                    tool.name(), CelEvaluationService.TOOL_SOURCE_EXTERNAL)) {
                visible.add(tool);
            }
        }
        return visible;
    }

    private List<McpSchemaVO.Tool> buildTools(List<McpToolConfigVO> toolConfigs) {
        List<McpSchemaVO.Tool> tools = new ArrayList<>();

        for (McpToolConfigVO toolConfigVO : toolConfigs) {
            McpToolProtocolConfigVO mcpToolProtocolConfigVO = toolConfigVO.getMcpToolProtocolConfigVO();
            List<McpToolProtocolConfigVO.ProtocolMapping> configs = normalizeRequestMappings(
                    mcpToolProtocolConfigVO == null ? null : mcpToolProtocolConfigVO.getRequestProtocolMappings());

            configs.sort(BY_SORT_ORDER);

            // 父子元素 Map parentPath -> List<Children>
            Map<String, List<McpToolProtocolConfigVO.ProtocolMapping>> childrenMap = new HashMap<>();

            List<McpToolProtocolConfigVO.ProtocolMapping> roots = new ArrayList<>();

            for (McpToolProtocolConfigVO.ProtocolMapping config : configs) {
                if (isRootPath(config.getParentPath())) {
                    roots.add(config);
                } else {
                    childrenMap.computeIfAbsent(config.getParentPath(), k -> new ArrayList<>()).add(config);
                }
            }

            roots.sort(BY_SORT_ORDER);

            // 构建输入结构
            Map<String, Object> properties = new LinkedHashMap<>();
            Set<String> required = new LinkedHashSet<>();

            for (McpToolProtocolConfigVO.ProtocolMapping root : roots) {
                properties.putIfAbsent(root.getFieldName(), buildProperty(root, childrenMap));
                if (Integer.valueOf(1).equals(root.getIsRequired())) {
                    required.add(root.getFieldName());
                }
            }

            // 构造函数
            McpSchemaVO.JsonSchema inputSchema = new McpSchemaVO.JsonSchema(
                    "object",
                    properties,
                    required.isEmpty() ? null : new ArrayList<>(required),
                    false,
                    null,
                    null);

            // 工具描述
            tools.add(new McpSchemaVO.Tool(toolConfigVO.getToolName(), toolConfigVO.getToolDescription(), inputSchema));
        }

        return tools;
    }

    private List<McpToolProtocolConfigVO.ProtocolMapping> normalizeRequestMappings(
            List<McpToolProtocolConfigVO.ProtocolMapping> configs) {
        if (configs == null || configs.isEmpty()) {
            return new ArrayList<>();
        }

        boolean hasRoot = configs.stream().anyMatch(config -> config != null && isRootPath(config.getParentPath()));
        if (hasRoot) {
            return new ArrayList<>(configs);
        }

        boolean legacyRequestWrapperOnly = configs.stream()
                .filter(Objects::nonNull)
                .allMatch(config -> "request".equals(config.getParentPath())
                        && config.getMcpPath() != null
                        && config.getMcpPath().startsWith("request."));

        if (!legacyRequestWrapperOnly) {
            return new ArrayList<>(configs);
        }

        List<McpToolProtocolConfigVO.ProtocolMapping> normalized = new ArrayList<>();
        for (McpToolProtocolConfigVO.ProtocolMapping config : configs) {
            normalized.add(McpToolProtocolConfigVO.ProtocolMapping.builder()
                    .mappingType(config.getMappingType())
                    .parentPath(null)
                    .fieldName(config.getFieldName())
                    .mcpPath(stripRequestPrefix(config.getMcpPath(), config.getFieldName()))
                    .mcpType(config.getMcpType())
                    .mcpDesc(config.getMcpDesc())
                    .isRequired(config.getIsRequired())
                    .sortOrder(config.getSortOrder())
                    .build());
        }
        return normalized;
    }

    private String stripRequestPrefix(String mcpPath, String fallback) {
        if (mcpPath == null) {
            return fallback;
        }
        String prefix = "request.";
        return mcpPath.startsWith(prefix) ? mcpPath.substring(prefix.length()) : mcpPath;
    }

    private boolean isRootPath(String parentPath) {
        return parentPath == null || parentPath.isBlank();
    }

    private Map<String, Object> buildProperty(McpToolProtocolConfigVO.ProtocolMapping current,
            Map<String, List<McpToolProtocolConfigVO.ProtocolMapping>> childrenMap) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", current.getMcpType());
        if (current.getMcpDesc() != null) {
            property.put("description", current.getMcpDesc());
        }

        // 校验孩子元素
        List<McpToolProtocolConfigVO.ProtocolMapping> children = childrenMap.get(current.getMcpPath());
        if (children != null && !children.isEmpty()) {
            Map<String, Object> props = new LinkedHashMap<>();
            Set<String> reqs = new LinkedHashSet<>();

            children.sort(BY_SORT_ORDER);

            for (McpToolProtocolConfigVO.ProtocolMapping child : children) {
                // 注意，buildProperty 嵌套递归，一层层的寻找，是否还有孩子元素（children）
                props.putIfAbsent(child.getFieldName(), buildProperty(child, childrenMap));
                if (Integer.valueOf(1).equals(child.getIsRequired())) {
                    reqs.add(child.getFieldName());
                }
            }

            property.put("properties", props);

            if (!reqs.isEmpty()) {
                property.put("required", new ArrayList<>(reqs));
            }

        }

        return property;
    }

}

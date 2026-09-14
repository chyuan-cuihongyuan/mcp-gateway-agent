package cn.chyuan.ai.domain.session.service.message.handler.impl;

import cn.chyuan.ai.domain.session.adapter.repository.ISessionRepository;
import cn.chyuan.ai.domain.session.model.valobj.McpSchemaVO;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolConfigVO;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolProtocolConfigVO;
import cn.chyuan.ai.domain.session.service.message.handler.IRequestHandler;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import cn.chyuan.ai.types.exception.AppException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 返回服务器支持的工具列表
 *
 * @author chyuan
 *         2025/12/20 11:29
 */
@Slf4j
@Service("toolsListHandler")
public class ToolsListHandler implements IRequestHandler {

    /** tools/list 分页大小（SELFLOOP3 loop-326，工单 0450/0451；MCP 规范 cursor 语义，默认 50=单页全量） */
    @Value("${mcp.tools.list-page-size:50}")
    private int listPageSize;

    @Resource
    private ISessionRepository repository;

    @Override
    public McpSchemaVO.JSONRPCResponse handle(String gatewayId, McpSchemaVO.JSONRPCRequest message) {

        // 1. 查询网关（gatewayId）下的工具列表配置
        List<McpToolConfigVO> mcpToolConfigVOS = repository.queryMcpGatewayToolConfigListByGatewayId(gatewayId);

        // 2. 构建工具列表
        List<McpSchemaVO.Tool> tools = buildTools(mcpToolConfigVOS);

        // 3. cursor 分页（MCP 规范：params.cursor opaque 游标，nextCursor 存在即还有下一页）
        ToolPage toolPage = pageTools(tools, extractCursor(message.params()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", toolPage.items());
        if (toolPage.hasNext()) {
            result.put("nextCursor", toolPage.items().get(toolPage.items().size() - 1).name());
        }

        return new McpSchemaVO.JSONRPCResponse("2.0", message.id(), result, null);
    }

    /** 分页结果（start=页起始下标，total=全集大小；线程安全：全部不可变） */
    private record ToolPage(int start, List<McpSchemaVO.Tool> items, int total) {
        boolean hasNext() {
            return start + items.size() < total;
        }
    }

    /** 按游标分页：cursor=null 从头；cursor=上次 nextCursor（某工具名）从其后继续；无效 cursor 报 INVALID_PARAMS */
    private ToolPage pageTools(List<McpSchemaVO.Tool> tools, String cursor) {
        int pageSize = Math.max(1, listPageSize);
        int start = 0;
        if (cursor != null && !cursor.isBlank()) {
            int idx = -1;
            for (int i = 0; i < tools.size(); i++) {
                if (cursor.equals(tools.get(i).name())) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) {
                throw new AppException(McpErrorCodes.INVALID_PARAMS, "无效的 tools/list 游标: " + cursor);
            }
            start = idx + 1;
        }
        int end = Math.min(start + pageSize, tools.size());
        return new ToolPage(start, tools.subList(start, end), tools.size());
    }

    /** params 里的 cursor 键（params 为 Map 时读取，兼容 null/其他形态） */
    @SuppressWarnings("unchecked")
    private String extractCursor(Object params) {
        if (params instanceof Map<?, ?> map && map.get("cursor") instanceof String cursor) {
            return cursor;
        }
        return null;
    }

    private List<McpSchemaVO.Tool> buildTools(List<McpToolConfigVO> toolConfigs) {
        List<McpSchemaVO.Tool> tools = new ArrayList<>();

        for (McpToolConfigVO toolConfigVO : toolConfigs) {
            McpToolProtocolConfigVO mcpToolProtocolConfigVO = toolConfigVO.getMcpToolProtocolConfigVO();
            // 协议配置缺失的工具跳过并告警（loop-326：单个坏配置不应拖垮整个列表接口）
            if (mcpToolProtocolConfigVO == null) {
                log.warn("工具协议配置缺失，跳过该工具: gatewayTool={}", toolConfigVO.getToolName());
                continue;
            }
            List<McpToolProtocolConfigVO.ProtocolMapping> configs = normalizeRequestMappings(mcpToolProtocolConfigVO
                    .getRequestProtocolMappings());

            // 排序
            configs.sort((o1, o2) -> {
                int s1 = o1.getSortOrder() != null ? o1.getSortOrder() : 0;
                int s2 = o2.getSortOrder() != null ? o2.getSortOrder() : 0;
                return Integer.compare(s1, s2);
            });

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

            // 排序
            roots.sort((o1, o2) -> {
                int s1 = o1.getSortOrder() != null ? o1.getSortOrder() : 0;
                int s2 = o2.getSortOrder() != null ? o2.getSortOrder() : 0;
                return Integer.compare(s1, s2);
            });

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

            // 排序
            children.sort((o1, o2) -> {
                int s1 = o1.getSortOrder() != null ? o1.getSortOrder() : 0;
                int s2 = o2.getSortOrder() != null ? o2.getSortOrder() : 0;
                return Integer.compare(s1, s2);
            });

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

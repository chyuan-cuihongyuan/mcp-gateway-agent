package cn.chyuan.ai.domain.session.service.message.handler.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具注解目录（b-38 / 工单 1190，借鉴 modelcontextprotocol spec 的
 * tool annotations 提示面：readOnlyHint / destructiveHint / idempotentHint /
 * openWorldHint）。
 * <p>
 * 配置形态（条目 CSV，每条 工具名=key:true|false）：
 * <pre>mcp.tool-annotations.entries: agent_order_query=readOnly:true,idempotent:true</pre>
 * 注解进入 TOOL_AUDIT 审计行（annotations= 字段），为采集侧按风险面
 * （只读/破坏性）过滤提供维度。未配置 = 无注解（审计行不带该字段），
 * 与 b-12 标签层同构；hints 不改变行为，仅供客户端/采集参考（spec 语义）。
 */
@Component
public class ToolAnnotationsCatalog {

    /** spec 四 hint 的紧凑别名（审计行省字） */
    private static final Map<String, String> KEY_ALIAS = Map.of(
            "readonly", "ro",
            "destructive", "destr",
            "idempotent", "idem",
            "openworld", "open");

    private final Map<String, Map<String, Boolean>> annotationsByTool = new LinkedHashMap<>();

    public ToolAnnotationsCatalog(
            @Value("${mcp.tool-annotations.entries:}") String entriesCsv) {
        if (entriesCsv != null && !entriesCsv.isBlank()) {
            Arrays.stream(entriesCsv.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(entry -> {
                        int eq = entry.indexOf('=');
                        if (eq <= 0 || eq == entry.length() - 1) {
                            return; // 非法条目静默跳过：注解层故障不得影响审计主链路
                        }
                        String tool = entry.substring(0, eq).trim();
                        Map<String, Boolean> hints = new LinkedHashMap<>();
                        Arrays.stream(entry.substring(eq + 1).split("\\|"))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .forEach(kv -> {
                                    int colon = kv.indexOf(':');
                                    if (colon <= 0 || colon == kv.length() - 1) {
                                        return;
                                    }
                                    String alias = KEY_ALIAS.get(kv.substring(0, colon).trim().toLowerCase());
                                    String rawVal = kv.substring(colon + 1).trim().toLowerCase();
                                    if (alias != null && (rawVal.equals("true") || rawVal.equals("false"))) {
                                        hints.put(alias, Boolean.parseBoolean(rawVal)); // 严格布尔：yes/1 等视为非法跳过
                                    }
                                });
                        if (!hints.isEmpty()) {
                            annotationsByTool.put(tool, hints);
                        }
                    });
        }
    }

    /** 工具注解（未配置返回空 Map） */
    public Map<String, Boolean> annotationsOf(String toolName) {
        if (toolName == null) {
            return Map.of();
        }
        return annotationsByTool.getOrDefault(toolName, Map.of());
    }

    /** 审计行 annotations= 字段值（形如 ro=true|destr=false；无注解返回 null） */
    public String auditValue(String toolName) {
        Map<String, Boolean> hints = annotationsOf(toolName);
        if (hints.isEmpty()) {
            return null;
        }
        return hints.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + "|" + b)
                .orElse(null);
    }
}

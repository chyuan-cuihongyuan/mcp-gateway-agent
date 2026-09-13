package cn.chyuan.ai.domain.session.service.message.handler.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具元数据标签目录（b-12 / 工单 1120，借鉴 mcp 生态工具分类惯例）。
 * <p>
 * 配置形态（条目 CSV，每条 工具名=标签|标签）：
 * <pre>mcp.tool-tags.entries: agent_order_query=order|finance,agent_invoice_query=invoice</pre>
 * 标签进入 TOOL_AUDIT 审计行（tags= 字段），为采集侧按业务域过滤提供维度。
 * 未配置 = 无标签（审计行不带 tags 字段），与 loop-233 consent 同构。
 */
@Component
public class ToolTagCatalog {

    private final Map<String, List<String>> tagsByTool = new LinkedHashMap<>();

    public ToolTagCatalog(
            @Value("${mcp.tool-tags.entries:}") String entriesCsv) {
        if (entriesCsv != null && !entriesCsv.isBlank()) {
            Arrays.stream(entriesCsv.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(entry -> {
                        int eq = entry.indexOf('=');
                        if (eq <= 0 || eq == entry.length() - 1) {
                            return; // 非法条目静默跳过：标签层故障不得影响审计主链路
                        }
                        String tool = entry.substring(0, eq).trim();
                        List<String> tags = Arrays.stream(entry.substring(eq + 1).split("\\|"))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .toList();
                        if (!tags.isEmpty()) {
                            tagsByTool.put(tool, tags);
                        }
                    });
        }
    }

    /** 工具标签（未配置返回空列表） */
    public List<String> tagsOf(String toolName) {
        if (toolName == null) {
            return List.of();
        }
        return tagsByTool.getOrDefault(toolName, List.of());
    }

    /** 审计行 tags= 字段值（无标签返回 null，调用方按需省略） */
    public String auditValue(String toolName) {
        List<String> tags = tagsOf(toolName);
        return tags.isEmpty() ? null : String.join("|", tags);
    }
}

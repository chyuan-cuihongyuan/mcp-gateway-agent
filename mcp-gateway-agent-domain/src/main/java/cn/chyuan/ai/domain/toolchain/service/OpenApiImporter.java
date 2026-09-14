package cn.chyuan.ai.domain.toolchain.service;

import cn.chyuan.ai.domain.toolchain.model.valobj.ImportReportVO;
import cn.chyuan.ai.domain.toolchain.model.valobj.ToolDefinitionVO;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAPI 导入器（工单 0331 AP1）。
 * OpenAPI 3 文档 JSON（info/paths/operations/parameters/requestBody）子集自解析
 * （不引解析库）→ 工具注册表条目；operationId 缺失自动派生（方法+路径），
 * 重复冲突拒绝，产出导入报告（新增/跳过/冲突）。
 * OpenAPI 规范 + langchain 工具抽象思想，domain 纯函数。
 */
public class OpenApiImporter {

    /** 解析导入：文档 JSON 字符串 → 工具定义列表 + 报告 */
    public ImportResult importDocument(String documentJson, List<ToolDefinitionVO> existing) {
        Map<String, Object> doc;
        try {
            doc = parseJsonObject(documentJson);
        } catch (RuntimeException e) {
            return fail("文档 JSON 非法: " + e.getMessage());
        }
        if (!doc.containsKey("paths")) {
            return fail("文档缺 paths 节点");
        }
        Object pathsNode = doc.get("paths");
        if (!(pathsNode instanceof Map)) {
            return fail("paths 节点非法");
        }
        String fingerprint = sha256(documentJson == null ? "" : documentJson);
        List<ToolDefinitionVO> definitions = new ArrayList<>();
        Map<?, ?> paths = (Map<?, ?>) pathsNode;
        for (Map.Entry<?, ?> pathEntry : paths.entrySet()) {
            String path = String.valueOf(pathEntry.getKey());
            if (!(pathEntry.getValue() instanceof Map)) {
                continue;
            }
            for (Map.Entry<?, ?> opEntry : ((Map<?, ?>) pathEntry.getValue()).entrySet()) {
                String method = String.valueOf(opEntry.getKey()).toLowerCase();
                if (!List.of("get", "post", "put", "delete", "patch").contains(method)) {
                    continue;
                }
                if (!(opEntry.getValue() instanceof Map)) {
                    continue;
                }
                Map<?, ?> operation = (Map<?, ?>) opEntry.getValue();
                definitions.add(toDefinition(operation, path, method, fingerprint));
            }
        }
        // 与既有注册表对账
        List<String> imported = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        Map<String, ToolDefinitionVO> existingByName = new LinkedHashMap<>();
        if (existing != null) {
            existing.forEach(e -> existingByName.put(e.getName(), e));
        }
        for (ToolDefinitionVO definition : definitions) {
            ToolDefinitionVO previous = existingByName.get(definition.getName());
            if (previous == null) {
                imported.add(definition.getName());
            } else if (fingerprint.equals(previous.getSourceFingerprint())) {
                skipped.add(definition.getName());
            } else {
                conflicts.add(definition.getName());
            }
        }
        return new ImportResult(definitions, ImportReportVO.builder()
                .imported(imported).skipped(skipped).conflicts(conflicts)
                .success(true).build());
    }

    /** operation → 工具定义（operationId 缺失派生：method + 路径规范化） */
    private ToolDefinitionVO toDefinition(Map<?, ?> operation, String path, String method, String fingerprint) {
        String operationId = operation.get("operationId") == null
                ? deriveOperationId(method, path)
                : String.valueOf(operation.get("operationId"));
        Map<String, Object> schema = new HashMap<>();
        Object parameters = operation.get("parameters");
        List<String> required = new ArrayList<>();
        Map<String, Object> properties = new LinkedHashMap<>();
        if (parameters instanceof List) {
            for (Object param : (List<?>) parameters) {
                if (!(param instanceof Map)) {
                    continue;
                }
                Map<?, ?> paramMap = (Map<?, ?>) param;
                String name = String.valueOf(paramMap.get("name"));
                Map<String, Object> property = new LinkedHashMap<>();
                property.put("type", paramMap.get("schema") instanceof Map
                        ? ((Map<?, ?>) paramMap.get("schema")).get("type") : "string");
                if (Boolean.TRUE.equals(paramMap.get("required"))) {
                    required.add(name);
                }
                properties.put(name, property);
            }
        }
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return ToolDefinitionVO.builder()
                .name(operationId)
                .description(operation.get("summary") == null ? "" : String.valueOf(operation.get("summary")))
                .method(method)
                .path(path)
                .parameterSchema(schema)
                .tags(operation.get("tags") instanceof List tags
                        ? tags.stream().map(String::valueOf).toList() : List.of())
                .sourceFingerprint(fingerprint)
                .build();
    }

    /** 派生 operationId：get_users_{id}（路径参数花括号转下划线段） */
    static String deriveOperationId(String method, String path) {
        String normalized = path.replace("/", "_").replace("{", "").replace("}", "");
        while (normalized.contains("__")) {
            normalized = normalized.replace("__", "_");
        }
        if (normalized.startsWith("_")) {
            normalized = normalized.substring(1);
        }
        return method + "_" + (normalized.isEmpty() ? "root" : normalized);
    }

    /** 极简 JSON 对象解析（嵌套 Map/List/字符串/数字/布尔，满足 OpenAPI 子集） */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parseJsonObject(String json) {
        return (Map<String, Object>) new MiniJsonParser(json).parse();
    }

    /** 解析失败报告 */
    private ImportResult fail(String error) {
        return new ImportResult(List.of(), ImportReportVO.builder()
                .imported(List.of()).skipped(List.of()).conflicts(List.of())
                .error(error).success(false).build());
    }

    static String sha256(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 导入结果：定义列表 + 报告 */
    public record ImportResult(List<ToolDefinitionVO> definitions, ImportReportVO report) {
    }
}

package cn.chyuan.ai.domain.generation.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 结构化输出守护纯函数内核（工单 0199 AA4，借鉴 instructor/OpenAI structured outputs）—
 * 最小 JSON Schema 校验子集（object 类型 + properties + required + 嵌套一层），
 * 解析失败/校验失败 → 修复挂点（{@link OutputRepairPort}，LLM 注入修复指令）→
 * 规则兜底（截取最外层 JSON 重解析）→ 仍失败返回 invalid（调用方按 -32025 拒绝）。
 *
 * @author chyuan
 */
public final class StructuredOutputGuard {

    private StructuredOutputGuard() {
    }

    /** 输出修复端口（LLM 版注入修复指令；测试/无 LLM 场景可为 null 走规则兜底） */
    public interface OutputRepairPort {
        /** 输入非法输出原文，返回修复后文本；失败返回 null */
        String repair(String rawOutput, String schemaError);
    }

    /** 校验结果 */
    public record GuardResult(boolean valid, String error, String normalizedJson) {
        public static GuardResult ok(String normalizedJson) {
            return new GuardResult(true, null, normalizedJson);
        }

        public static GuardResult fail(String error) {
            return new GuardResult(false, error, null);
        }
    }

    /**
     * 守护主流程：校验 → 失败则修复挂点 → 再失败规则兜底（截取最外层 JSON）→ 终判。
     */
    public static GuardResult enforce(String rawOutput, JsonSchemaLite schema,
            OutputRepairPort repairPort) {
        GuardResult direct = check(rawOutput, schema);
        if (direct.valid()) {
            return direct;
        }
        if (repairPort != null) {
            try {
                String repaired = repairPort.repair(rawOutput, direct.error());
                GuardResult afterRepair = check(repaired, schema);
                if (afterRepair.valid()) {
                    return afterRepair;
                }
            } catch (Exception ignored) {
                // 修复挂点故障不阻断，走规则兜底
            }
        }
        // 规则兜底：截取最外层 {...} 或 [...] 重解析（模型带前导说明文字的常见失败态）
        String extracted = extractOutermostJson(rawOutput);
        if (extracted != null) {
            GuardResult fallback = check(extracted, schema);
            if (fallback.valid()) {
                return fallback;
            }
        }
        return direct;
    }

    /** 单次校验：JSON 可解析 + schema 合式 */
    public static GuardResult check(String rawOutput, JsonSchemaLite schema) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return GuardResult.fail("输出为空");
        }
        Object parsed;
        try {
            parsed = com.alibaba.fastjson.JSON.parse(rawOutput);
        } catch (Exception e) {
            return GuardResult.fail("输出不是合法 JSON: " + e.getMessage());
        }
        if (parsed == null) {
            return GuardResult.fail("输出为 null");
        }
        return schema == null ? GuardResult.ok(rawOutput) : schema.validate(parsed);
    }

    /** 截取最外层 JSON（{...} 或 [...]，首个开括号到最后一个闭括号） */
    public static String extractOutermostJson(String text) {
        if (text == null) {
            return null;
        }
        int objStart = text.indexOf('{');
        int arrStart = text.indexOf('[');
        int start;
        if (objStart < 0 && arrStart < 0) {
            return null;
        } else if (objStart < 0) {
            start = arrStart;
        } else if (arrStart < 0) {
            start = objStart;
        } else {
            start = Math.min(objStart, arrStart);
        }
        char open = text.charAt(start);
        char close = open == '{' ? '}' : ']';
        int end = text.lastIndexOf(close);
        if (end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    /**
     * 最小 JSON Schema 子集：type=object + properties（含一层嵌套 object）+ required。
     */
    public record JsonSchemaLite(Map<String, String> propertyTypes, Set<String> required) {

        public JsonSchemaLite {
            propertyTypes = propertyTypes == null ? Map.of() : Map.copyOf(propertyTypes);
            required = required == null ? Set.of() : Set.copyOf(required);
        }

        public GuardResult validate(Object parsed) {
            if (!(parsed instanceof com.alibaba.fastjson.JSONObject obj)) {
                return GuardResult.fail("期望 object，实际 " + typeName(parsed));
            }
            for (String req : required) {
                if (!obj.containsKey(req)) {
                    return GuardResult.fail("缺少必填字段: " + req);
                }
            }
            for (Map.Entry<String, Object> entry : obj.entrySet()) {
                String declaredType = propertyTypes.get(entry.getKey());
                if (declaredType == null) {
                    continue;
                }
                String actual = typeName(entry.getValue());
                if (!matches(declaredType, actual, entry.getValue())) {
                    return GuardResult.fail(
                            "字段 " + entry.getKey() + " 期望 " + declaredType + "，实际 " + actual);
                }
            }
            return GuardResult.ok(obj.toJSONString());
        }

        private static boolean matches(String declared, String actual, Object value) {
            return switch (declared) {
                case "string" -> "string".equals(actual);
                case "number" -> "number".equals(actual);
                case "integer" -> "number".equals(actual) && ((Number) value).doubleValue() % 1 == 0;
                case "boolean" -> "boolean".equals(actual);
                case "array" -> value instanceof List;
                case "object" -> value instanceof Map;
                default -> true;
            };
        }

        private static String typeName(Object value) {
            if (value instanceof String) {
                return "string";
            }
            if (value instanceof Number) {
                return "number";
            }
            if (value instanceof Boolean) {
                return "boolean";
            }
            if (value instanceof List) {
                return "array";
            }
            if (value instanceof Map) {
                return "object";
            }
            return value == null ? "null" : value.getClass().getSimpleName();
        }
    }

    /** 从 fastjson schema JSON 构造（response_format.json_schema.schema 形态的宽松解析） */
    @SuppressWarnings("unchecked")
    public static JsonSchemaLite parseSchema(String schemaJson) {
        com.alibaba.fastjson.JSONObject root = com.alibaba.fastjson.JSON.parseObject(schemaJson);
        if (root == null) {
            return null;
        }
        Map<String, String> types = new java.util.LinkedHashMap<>();
        Set<String> required = Set.of();
        Object props = root.get("properties");
        if (props instanceof Map<?, ?> propsMap) {
            for (Map.Entry<?, ?> e : ((Map<Object, Object>) propsMap).entrySet()) {
                String name = String.valueOf(e.getKey());
                Object spec = e.getValue();
                if (spec instanceof Map<?, ?> specMap) {
                    Object type = specMap.get("type");
                    if (type != null) {
                        types.put(name, String.valueOf(type));
                    }
                }
            }
        }
        if (root.get("required") instanceof List<?> reqList) {
            required = reqList.stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet());
        }
        return new JsonSchemaLite(types, required);
    }
}

package cn.chyuan.ai.domain.configcenter.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 配置 JSON Schema 轻量校验器（工单 0253 AG3，借鉴 Nacos 配置校验/Vault 值约束）—
 * 支持六类约束子集：type（string/number/boolean/object/array）、required、properties（嵌套）、
 * enum、minimum/maximum（number）、pattern（string 正则）。报错带 $ 路径，纯函数零依赖。
 *
 * @author chyuan
 */
public final class JsonSchemaLite {

    private static final List<String> LEGAL_TYPES = List.of("string", "number", "boolean", "object", "array");

    private JsonSchemaLite() {
    }

    /** 校验 json 是否满足 schemaJson，返回错误清单（空 = 通过；json/schema 非法本身即错误） */
    public static List<String> validate(String json, String schemaJson) {
        List<String> errors = new ArrayList<>();
        JSONObject schema;
        Object document;
        try {
            schema = JSON.parseObject(schemaJson);
            document = JSON.parse(json);
        } catch (Exception e) {
            errors.add("$: JSON 解析失败: " + e.getMessage());
            return errors;
        }
        if (schema == null) {
            errors.add("$: schema 非法（空）");
            return errors;
        }
        validateNode(document, schema, "$", errors);
        return errors;
    }

    /** 校验 schema 本身合法性（type 白名单 + 必须为对象），返回错误清单 */
    public static List<String> validateSchema(String schemaJson) {
        List<String> errors = new ArrayList<>();
        JSONObject schema;
        try {
            schema = JSON.parseObject(schemaJson);
        } catch (Exception e) {
            errors.add("schema 解析失败: " + e.getMessage());
            return errors;
        }
        if (schema == null) {
            errors.add("schema 非法（空）");
            return errors;
        }
        String type = schema.getString("type");
        if (type != null && !LEGAL_TYPES.contains(type)) {
            errors.add("schema.type 非法: " + type);
        }
        if (schema.containsKey("pattern")) {
            try {
                java.util.regex.Pattern.compile(schema.getString("pattern"));
            } catch (Exception e) {
                errors.add("schema.pattern 非法正则: " + e.getMessage());
            }
        }
        return errors;
    }

    private static void validateNode(Object value, JSONObject schema, String path, List<String> errors) {
        String type = schema.getString("type");
        if (type != null && !typeMatches(value, type)) {
            errors.add(path + ": 类型应为 " + type + "，实际 " + typeName(value));
            return;
        }
        if (value instanceof JSONObject obj) {
            validateObject(obj, schema, path, errors);
        } else if (value instanceof JSONArray arr) {
            validateArray(arr, schema, path, errors);
        } else if (value instanceof String s) {
            validateString(s, schema, path, errors);
        } else if (isNumber(value)) {
            validateNumber(((BigDecimal) toNumber(value)), schema, path, errors);
        }
    }

    private static void validateObject(JSONObject obj, JSONObject schema, String path, List<String> errors) {
        JSONArray required = schema.getJSONArray("required");
        if (required != null) {
            for (int i = 0; i < required.size(); i++) {
                String key = required.getString(i);
                if (!obj.containsKey(key)) {
                    errors.add(path + "." + key + ": 缺少必填字段");
                }
            }
        }
        JSONObject properties = schema.getJSONObject("properties");
        if (properties != null) {
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                String childPath = path + "." + entry.getKey();
                if (!(entry.getValue() instanceof JSONObject childSchema)) {
                    errors.add(childPath + ": properties 子项必须是对象");
                    continue;
                }
                if (obj.containsKey(entry.getKey())) {
                    validateNode(obj.get(entry.getKey()), childSchema, childPath, errors);
                }
            }
        }
    }

    private static void validateArray(JSONArray arr, JSONObject schema, String path, List<String> errors) {
        JSONObject items = schema.getJSONObject("items");
        if (items == null) {
            return;
        }
        for (int i = 0; i < arr.size(); i++) {
            validateNode(arr.get(i), items, path + "[" + i + "]", errors);
        }
    }

    private static void validateString(String s, JSONObject schema, String path, List<String> errors) {
        JSONArray enumValues = schema.getJSONArray("enum");
        if (enumValues != null && !containsString(enumValues, s)) {
            errors.add(path + ": 值 " + s + " 不在枚举内");
        }
        String pattern = schema.getString("pattern");
        if (pattern != null && !java.util.regex.Pattern.compile(pattern).matcher(s).find()) {
            errors.add(path + ": 值不匹配 pattern " + pattern);
        }
    }

    private static void validateNumber(BigDecimal n, JSONObject schema, String path, List<String> errors) {
        BigDecimal minimum = schema.getBigDecimal("minimum");
        if (minimum != null && n.compareTo(minimum) < 0) {
            errors.add(path + ": 值 " + n + " 小于 minimum " + minimum);
        }
        BigDecimal maximum = schema.getBigDecimal("maximum");
        if (maximum != null && n.compareTo(maximum) > 0) {
            errors.add(path + ": 值 " + n + " 大于 maximum " + maximum);
        }
    }

    private static boolean typeMatches(Object value, String type) {
        return switch (type) {
            case "string" -> value instanceof String;
            case "number" -> isNumber(value);
            case "boolean" -> value instanceof Boolean;
            case "object" -> value instanceof JSONObject;
            case "array" -> value instanceof JSONArray;
            default -> true;
        };
    }

    private static boolean isNumber(Object value) {
        return value instanceof Number || value instanceof BigDecimal;
    }

    private static Object toNumber(Object value) {
        return value instanceof Number n ? new BigDecimal(n.toString()) : value;
    }

    private static String typeName(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "string";
        }
        if (isNumber(value)) {
            return "number";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof JSONObject) {
            return "object";
        }
        if (value instanceof JSONArray) {
            return "array";
        }
        return value.getClass().getSimpleName();
    }

    private static boolean containsString(JSONArray arr, String s) {
        for (int i = 0; i < arr.size(); i++) {
            if (s.equals(arr.getString(i))) {
                return true;
            }
        }
        return false;
    }
}

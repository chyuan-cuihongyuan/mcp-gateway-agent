package cn.chyuan.ai.domain.toolchain.service;

import cn.chyuan.ai.domain.toolchain.model.valobj.SchemaErrorVO;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JSON Schema 校验内核（工单 0332 AP2，pydantic 校验错误定位思想）。
 * 子集校验：type/required/properties/enum/minimum/maximum + 嵌套对象/数组递归；
 * 多错误全量收集，错误带 JSON 路径（$.a.b[0]）。domain 纯函数。
 */
public class JsonSchemaValidator {

    /** 校验入口：非对象入参直接报根级类型错误 */
    public List<SchemaErrorVO> validate(Object input, Map<String, Object> schema) {
        List<SchemaErrorVO> errors = new ArrayList<>();
        if (schema == null || schema.isEmpty()) {
            return errors;
        }
        validateNode(input, schema, "$", errors);
        return errors;
    }

    @SuppressWarnings("unchecked")
    private void validateNode(Object value, Map<String, Object> schema, String path,
                              List<SchemaErrorVO> errors) {
        String expectedType = String.valueOf(schema.getOrDefault("type", "object"));
        if ("object".equals(expectedType)) {
            if (!(value instanceof Map)) {
                errors.add(error(path, "TYPE_MISMATCH", "应为 object，实际 " + typeName(value)));
                return;
            }
            Map<String, Object> object = (Map<String, Object>) value;
            Object required = schema.get("required");
            if (required instanceof List) {
                for (Object field : (List<?>) required) {
                    if (!object.containsKey(String.valueOf(field))) {
                        errors.add(error(path, "REQUIRED_MISSING", "缺必填字段 " + field));
                    }
                }
            }
            Object properties = schema.get("properties");
            if (properties instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) properties).entrySet()) {
                    String field = String.valueOf(entry.getKey());
                    if (object.containsKey(field) && entry.getValue() instanceof Map) {
                        validateNode(object.get(field), (Map<String, Object>) entry.getValue(),
                                path + "." + field, errors);
                    }
                }
            }
            return;
        }
        if ("array".equals(expectedType)) {
            if (!(value instanceof List)) {
                errors.add(error(path, "TYPE_MISMATCH", "应为 array，实际 " + typeName(value)));
                return;
            }
            Object items = schema.get("items");
            if (items instanceof Map) {
                List<?> list = (List<?>) value;
                for (int i = 0; i < list.size(); i++) {
                    validateNode(list.get(i), (Map<String, Object>) items, path + "[" + i + "]", errors);
                }
            }
            return;
        }
        // 标量类型：string/number/integer/boolean
        if (!scalarMatches(expectedType, value)) {
            errors.add(error(path, "TYPE_MISMATCH", "应为 " + expectedType + "，实际 " + typeName(value)));
            return;
        }
        Object enumValues = schema.get("enum");
        if (enumValues instanceof List && !((List<?>) enumValues).contains(value)) {
            errors.add(error(path, "ENUM_VIOLATION", "值不在枚举内: " + value));
        }
        if (value instanceof Number number) {
            if (schema.get("minimum") instanceof Number min && number.doubleValue() < min.doubleValue()) {
                errors.add(error(path, "MIN_VIOLATION", "小于最小值 " + min));
            }
            if (schema.get("maximum") instanceof Number max && number.doubleValue() > max.doubleValue()) {
                errors.add(error(path, "MAX_VIOLATION", "大于最大值 " + max));
            }
        }
    }

    private boolean scalarMatches(String type, Object value) {
        return switch (type) {
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Long || value instanceof Integer;
            case "boolean" -> value instanceof Boolean;
            default -> true;
        };
    }

    private String typeName(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Map) {
            return "object";
        }
        if (value instanceof List) {
            return "array";
        }
        return value.getClass().getSimpleName();
    }

    private SchemaErrorVO error(String path, String code, String message) {
        return SchemaErrorVO.builder().path(path).code(code).message(message).build();
    }
}

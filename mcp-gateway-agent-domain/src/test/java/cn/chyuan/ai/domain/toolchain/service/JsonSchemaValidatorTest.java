package cn.chyuan.ai.domain.toolchain.service;

import cn.chyuan.ai.domain.toolchain.model.valobj.SchemaErrorVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON Schema 校验内核单测（工单 0332 AP2）：类型/必填/枚举/嵌套路径/多错误。
 */
class JsonSchemaValidatorTest {

    private final JsonSchemaValidator validator = new JsonSchemaValidator();

    private Map<String, Object> schema() {
        return Map.of(
                "type", "object",
                "required", List.of("name", "age"),
                "properties", Map.of(
                        "name", Map.of("type", "string"),
                        "age", Map.of("type", "integer", "minimum", 0, "maximum", 150),
                        "role", Map.of("type", "string", "enum", List.of("admin", "user")),
                        "tags", Map.of("type", "array", "items", Map.of("type", "string")),
                        "addr", Map.of("type", "object", "required", List.of("city"),
                                "properties", Map.of("city", Map.of("type", "string")))));
    }

    @Test
    void 合法入参零错误() {
        List<SchemaErrorVO> errors = validator.validate(Map.of(
                "name", "张三", "age", 20, "role", "admin",
                "tags", List.of("a", "b"), "addr", Map.of("city", "深圳")), schema());
        assertTrue(errors.isEmpty(), "合法入参应零错误: " + errors);
    }

    @Test
    void 各类错误与路径定位() {
        List<SchemaErrorVO> errors = validator.validate(Map.of(
                "name", 123,
                "role", "ghost",
                "age", -1,
                "tags", List.of("ok", 7),
                "addr", Map.of()), schema());
        // name 类型 / age 缺? age 提供了但越界 / role 枚举 / tags[1] 类型 / addr.city 缺
        assertTrue(errors.stream().anyMatch(e -> e.getPath().equals("$.name")
                && "TYPE_MISMATCH".equals(e.getCode())));
        assertTrue(errors.stream().anyMatch(e -> e.getPath().equals("$.age")
                && "MIN_VIOLATION".equals(e.getCode())));
        assertTrue(errors.stream().anyMatch(e -> e.getPath().equals("$.role")
                && "ENUM_VIOLATION".equals(e.getCode())));
        assertTrue(errors.stream().anyMatch(e -> e.getPath().equals("$.tags[1]")
                && "TYPE_MISMATCH".equals(e.getCode())));
        assertTrue(errors.stream().anyMatch(e -> e.getPath().equals("$.addr")
                && "REQUIRED_MISSING".equals(e.getCode()) && e.getMessage().contains("city")));
    }

    @Test
    void 必填缺失与根级类型错误多错误全量() {
        List<SchemaErrorVO> missing = validator.validate(Map.of("name", "x"), schema());
        assertEquals(1, missing.size());
        assertEquals("$", missing.get(0).getPath(), "根级必填缺失路径为 $");
        assertEquals("REQUIRED_MISSING", missing.get(0).getCode());
        // 根级非对象
        List<SchemaErrorVO> root = validator.validate("字符串", schema());
        assertEquals(1, root.size());
        assertEquals("$", root.get(0).getPath());
        assertTrue(root.get(0).getMessage().contains("object"));
        // 空 schema 零错误（无约束）
        assertTrue(validator.validate(Map.of(), Map.of()).isEmpty());
    }
}

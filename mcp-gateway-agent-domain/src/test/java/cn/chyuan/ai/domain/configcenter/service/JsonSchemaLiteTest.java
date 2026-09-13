package cn.chyuan.ai.domain.configcenter.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON Schema 轻量校验器与发布门单测（工单 0253 AG3）：六类约束/嵌套/报错路径/schema 自检。
 */
class JsonSchemaLiteTest {

    private static final String SCHEMA = """
            {
              "type": "object",
              "required": ["qps", "mode"],
              "properties": {
                "qps": {"type": "number", "minimum": 1, "maximum": 100},
                "mode": {"type": "string", "enum": ["fixed", "sliding"]},
                "name": {"type": "string", "pattern": "^[a-z]+$"},
                "flag": {"type": "boolean"},
                "tags": {"type": "array", "items": {"type": "string"}},
                "nested": {"type": "object", "properties": {"deep": {"type": "number"}}}
              }
            }
            """;

    @Test
    void 全约束通过() {
        String json = """
                {"qps": 50, "mode": "fixed", "name": "abc", "flag": true,
                 "tags": ["a", "b"], "nested": {"deep": 1.5}}
                """;
        assertTrue(JsonSchemaLite.validate(json, SCHEMA).isEmpty());
    }

    @Test
    void 类型_必填_范围_枚举_正则_嵌套全报错带路径() {
        String json = """
                {"qps": 0, "mode": "bogus", "name": "ABC", "flag": "yes",
                 "tags": [1], "nested": {"deep": "x"}}
                """;
        List<String> errors = JsonSchemaLite.validate(json, SCHEMA);
        // qps minimum + 缺 required 不会触发（qps 存在）；mode 枚举；pattern；boolean 类型；array item 类型；嵌套类型
        assertTrue(errors.stream().anyMatch(e -> e.contains("$.qps") && e.contains("minimum")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("$.mode") && e.contains("枚举")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("$.name") && e.contains("pattern")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("$.flag") && e.contains("boolean")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("$.tags[0]")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("$.nested.deep")));
    }

    @Test
    void 必填缺失与类型不符() {
        List<String> errors = JsonSchemaLite.validate("{\"qps\": \"nan\"}", SCHEMA);
        assertTrue(errors.stream().anyMatch(e -> e.contains("缺少必填字段") && e.contains("mode")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("$.qps") && e.contains("类型应为 number")));
    }

    @Test
    void 非法JSON与schema自检() {
        assertFalse(JsonSchemaLite.validate("not-json", SCHEMA).isEmpty());
        // schema 自检：非法 type / 非法正则
        assertTrue(JsonSchemaLite.validateSchema("{\"type\":\"bogus\"}").size() == 1);
        assertTrue(JsonSchemaLite.validateSchema("{\"pattern\":\"[[\"}").size() == 1);
        assertTrue(JsonSchemaLite.validateSchema("{\"type\":\"string\"}").isEmpty());
    }
}

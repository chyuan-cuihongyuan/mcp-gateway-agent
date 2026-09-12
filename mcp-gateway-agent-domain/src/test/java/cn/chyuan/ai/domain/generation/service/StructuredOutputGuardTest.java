package cn.chyuan.ai.domain.generation.service;

import cn.chyuan.ai.domain.generation.service.StructuredOutputGuard.GuardResult;
import cn.chyuan.ai.domain.generation.service.StructuredOutputGuard.JsonSchemaLite;
import cn.chyuan.ai.domain.generation.service.StructuredOutputGuard.OutputRepairPort;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构化输出守护单测（工单 0199 AA4）：schema 校验/修复重试/规则兜底/最外层截取。
 */
class StructuredOutputGuardTest {

    private static final JsonSchemaLite SCHEMA =
            new JsonSchemaLite(Map.of("name", "string", "age", "integer"), Set.of("name"));

    @Test
    void schema校验路径() {
        assertTrue(StructuredOutputGuard.check("{\"name\":\"a\",\"age\":3}", SCHEMA).valid());
        // 缺必填
        assertFalse(StructuredOutputGuard.check("{\"age\":3}", SCHEMA).valid());
        // 类型不符
        assertFalse(StructuredOutputGuard.check("{\"name\":1}", SCHEMA).valid());
        // integer 拒小数
        assertFalse(StructuredOutputGuard.check("{\"name\":\"a\",\"age\":3.5}", SCHEMA).valid());
        // 非 object
        assertFalse(StructuredOutputGuard.check("[1,2]", SCHEMA).valid());
        // 空与坏 JSON
        assertFalse(StructuredOutputGuard.check("", SCHEMA).valid());
        assertFalse(StructuredOutputGuard.check("{bad", SCHEMA).valid());
        // schema 为 null 放行（只校验 JSON 合法性）
        assertTrue(StructuredOutputGuard.check("{\"x\":1}", null).valid());
    }

    @Test
    void 修复挂点一次重试() {
        AtomicInteger repairs = new AtomicInteger();
        OutputRepairPort llmRepair = (raw, err) -> {
            repairs.incrementAndGet();
            return "{\"name\":\"fixed\",\"age\":1}";
        };
        GuardResult result = StructuredOutputGuard.enforce(
                "说明文字 {\"age\":2} 望采纳", SCHEMA, llmRepair);
        // 直查失败 → 修复挂点成功
        assertTrue(result.valid());
        assertEquals("fixed", com.alibaba.fastjson.JSON.parseObject(result.normalizedJson()).get("name"));
        assertEquals(1, repairs.get());
    }

    @Test
    void 无挂点时规则兜底截取最外层() {
        GuardResult result = StructuredOutputGuard.enforce(
                "结果如下：{\"name\":\"兜底\",\"age\":2}（以上）", SCHEMA, null);
        assertTrue(result.valid());
        assertTrue(result.normalizedJson().contains("兜底"));
    }

    @Test
    void 两次失败终判invalid() {
        OutputRepairPort badRepair = (raw, err) -> "还是不对";
        GuardResult result = StructuredOutputGuard.enforce("完全没有 JSON", SCHEMA, badRepair);
        assertFalse(result.valid());
        assertNull(result.normalizedJson());
    }

    @Test
    void 最外层JSON截取() {
        assertEquals("{\"a\":1}", StructuredOutputGuard.extractOutermostJson("x {\"a\":1} y"));
        assertEquals("[1,2]", StructuredOutputGuard.extractOutermostJson("前 [1,2] 后"));
        assertNull(StructuredOutputGuard.extractOutermostJson("没有括号"));
        assertNull(StructuredOutputGuard.extractOutermostJson(null));
        assertNull(StructuredOutputGuard.extractOutermostJson("{ 没闭"));
    }

    @Test
    void schema解析与挂点故障降级() {
        JsonSchemaLite parsed = StructuredOutputGuard.parseSchema(
                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}");
        assertEquals(Set.of("name"), parsed.required());
        // 挂点抛异常不阻断 → 规则兜底
        OutputRepairPort throwing = (raw, err) -> {
            throw new IllegalStateException("llm down");
        };
        assertTrue(StructuredOutputGuard.enforce("前置 {\"name\":\"ok\"}", parsed, throwing).valid());
    }
}

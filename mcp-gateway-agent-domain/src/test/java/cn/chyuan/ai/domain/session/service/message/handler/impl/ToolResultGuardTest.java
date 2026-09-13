package cn.chyuan.ai.domain.session.service.message.handler.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * b-19 工具返回体积护栏契约：以内透传、超限截断+标记、0=关闭、null 透传、
 * 边界值（恰等于上限）。
 */
@DisplayName("ToolResultGuard 护栏契约（b-19）")
class ToolResultGuardTest {

    private ToolResultGuard guardWith(int maxChars) {
        ToolResultGuard guard = new ToolResultGuard();
        ReflectionTestUtils.setField(guard, "maxChars", maxChars);
        return guard;
    }

    @Test
    @DisplayName("未超限：原样透传（同一实例）")
    void underLimitPassesThrough() {
        ToolResultGuard guard = guardWith(100);
        Object result = Map.of("ok", 1);

        assertSame(result, guard.guard(result, "agent_order_query"));
    }

    @Test
    @DisplayName("超限：截断至上限并附 spilled 标记（含损失量）")
    void overLimitTruncatedWithMarker() {
        ToolResultGuard guard = guardWith(50);
        String big = "x".repeat(80);

        Object guarded = guard.guard(big, "agent_dump");
        String text = String.valueOf(guarded);

        assertNotEquals(big, guarded);
        assertEquals(50, text.indexOf("\n…[spilled"));
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("截断 30 字符（上限 50）"));
    }

    @Test
    @DisplayName("0=关闭：超限也原样透传")
    void disabledPassesEverything() {
        ToolResultGuard guard = guardWith(0);
        String big = "y".repeat(1000);

        assertSame(big, guard.guard(big, "agent_dump"));
    }

    @Test
    @DisplayName("null 透传")
    void nullPassesThrough() {
        assertNull(guardWith(50).guard(null, "t"));
    }

    @Test
    @DisplayName("恰好等于上限：不截断")
    void exactlyAtLimitPasses() {
        ToolResultGuard guard = guardWith(10);
        String exact = "z".repeat(10);

        assertSame(exact, guard.guard(exact, "t"));
    }

    @Test
    @DisplayName("列表型结果同样按文本形态护栏")
    void listResultsGuardedAsText() {
        ToolResultGuard guard = guardWith(10);
        Object result = List.of("a".repeat(30));

        String text = String.valueOf(guard.guard(result, "t"));
        assertNotEquals(result, text);
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("[spilled"));
    }
}

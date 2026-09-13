package cn.chyuan.ai.domain.policy.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表达式求值内核单测（工单 0260 AH1）：优先级/短路/嵌套路径/正则/集合/超限/畸形。
 */
class ExprKernelTest {

    private static Map<String, Object> ctx() {
        Map<String, Object> env = new HashMap<>();
        env.put("tier", "gold");
        env.put("qps", 100);
        Map<String, Object> root = new HashMap<>();
        root.put("subject", "key-1");
        root.put("object", "gpt-5");
        root.put("action", "chat");
        root.put("env", env);
        return root;
    }

    @Test
    void 字面量与比较() {
        assertTrue(ExprKernel.evaluateBoolean("1 < 2", Map.of()));
        assertTrue(ExprKernel.evaluateBoolean("3 >= 3", Map.of()));
        assertTrue(ExprKernel.evaluateBoolean("'a' == 'a'", Map.of()));
        assertTrue(ExprKernel.evaluateBoolean("'a' != 'b'", Map.of()));
        assertTrue(ExprKernel.evaluateBoolean("true && !false", Map.of()));
        assertFalse(ExprKernel.evaluateBoolean("2 > 5", Map.of()));
        // 非布尔结果便捷方法抛异常
        assertThrows(ExprKernel.ExprException.class, () -> ExprKernel.evaluateBoolean("1 + 1", Map.of()));
    }

    @Test
    void 属性路径与集合与正则() {
        assertTrue(ExprKernel.evaluateBoolean("env.tier == 'gold'", ctx()));
        assertTrue(ExprKernel.evaluateBoolean("env.qps >= 100", ctx()));
        assertTrue(ExprKernel.evaluateBoolean("env.tier in {'gold', 'silver'}", ctx()));
        assertFalse(ExprKernel.evaluateBoolean("env.tier in {'bronze'}", ctx()));
        assertTrue(ExprKernel.evaluateBoolean("object matches '^gpt-.*'", ctx()));
        assertFalse(ExprKernel.evaluateBoolean("subject matches '^other'", ctx()));
        // 缺失路径求值 null
        assertEquals(null, ExprKernel.evaluate("env.missing.deep", ctx()));
    }

    @Test
    void 优先级与括号与短路() {
        // && 优先于 ||
        assertTrue(ExprKernel.evaluateBoolean("false && false || true", Map.of()));
        // 括号改变结合
        assertFalse(ExprKernel.evaluateBoolean("false && (false || true)", Map.of()));
        // 短路：右侧不可比较类型不被求值（|| 左真直接返回）
        assertTrue(ExprKernel.evaluateBoolean("true || 1 < 'x'", Map.of()));
    }

    @Test
    void 超限与畸形拒绝() {
        assertThrows(ExprKernel.ExprException.class, () -> ExprKernel.evaluate(null, Map.of()));
        assertThrows(ExprKernel.ExprException.class, () -> ExprKernel.evaluate("  ", Map.of()));
        // 超长
        StringBuilder longExpr = new StringBuilder("subject == '");
        longExpr.append("x".repeat(ExprKernel.MAX_LENGTH));
        assertThrows(ExprKernel.ExprException.class, () -> ExprKernel.evaluate(longExpr.toString(), Map.of()));
        // 深度过深（括号嵌套）
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i <= ExprKernel.MAX_DEPTH + 1; i++) {
            deep.append("(");
        }
        deep.append("true");
        deep.append(")".repeat(ExprKernel.MAX_DEPTH + 2));
        assertThrows(ExprKernel.ExprException.class, () -> ExprKernel.evaluate(deep.toString(), Map.of()));
        // 畸形：单 =、未闭合字符串、非法字符、缺操作数
        assertThrows(ExprKernel.ExprException.class, () -> ExprKernel.evaluate("a = 1", Map.of()));
        assertThrows(ExprKernel.ExprException.class, () -> ExprKernel.evaluate("'unclosed", Map.of()));
        assertThrows(ExprKernel.ExprException.class, () -> ExprKernel.evaluate("a @ b", Map.of()));
        assertThrows(ExprKernel.ExprException.class, () -> ExprKernel.evaluate("1 <", Map.of()));
        assertThrows(ExprKernel.ExprException.class, () -> ExprKernel.evaluate("subject matches '^['", ctx()));
    }

    @Test
    void validate预检与类型比较边界() {
        ExprKernel.validate("env.qps > 10 && subject in {'a','b'}");
        // 数字 vs 字符串比较拒绝
        assertThrows(ExprKernel.ExprException.class, () -> ExprKernel.evaluate("1 < 'x'", Map.of()));
        // 字符串字典序
        assertTrue(ExprKernel.evaluateBoolean("'a' < 'b'", Map.of()));
        // List.of 上下文不可变没问题
        assertTrue(ExprKernel.evaluateBoolean("!(false)", List.of().isEmpty() ? Map.of() : Map.of()));
    }
}

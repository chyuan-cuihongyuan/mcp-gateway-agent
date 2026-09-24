package cn.chyuan.ai.domain.jqkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * jq 内核测试（工单 0756-0762 CL1-CL7，jq 思想）。
 * 词法/解析优先级/路径求值 optional/内建函数/构造插值/reduce foreach/错误流 truthy 定序。
 */
class JqKernelTest {

    private static List<String> run(String program, Object input) {
        JqEvaluator evaluator = new JqEvaluator(JqParser.of(program).parse());
        return evaluator.run(input).stream().map(JqEvaluator::render).toList();
    }

    @Test
    void lexerTokensAndErrors() {
        List<JqLexer.Token> tokens = new JqLexer(".a | .b[0]?").lex();
        assertEquals(JqLexer.Kind.DOT, tokens.get(0).kind());
        assertEquals(JqLexer.Kind.IDENT, tokens.get(1).kind());
        assertEquals(JqLexer.Kind.PIPE, tokens.get(2).kind());
        assertEquals(JqLexer.Kind.EOF, tokens.get(tokens.size() - 1).kind());
        assertThrows(IllegalArgumentException.class, () -> new JqLexer(".a @ .b").lex(), "非法字符报定位");
        assertThrows(IllegalArgumentException.class, () -> new JqLexer("$ x").lex(), "变量名缺失");
        assertThrows(IllegalArgumentException.class, () -> new JqLexer("\"open").lex(), "字符串未闭合");
    }

    @Test
    void parserPrecedenceAndResidue() {
        JqParser.Node node = JqParser.of(".a + 1 | .b").parse();
        assertInstanceOf(JqParser.Pipe.class, node);
        assertInstanceOf(JqParser.Binary.class, ((JqParser.Pipe) node).left());
        assertEquals("7", run("1 + 2 * 3", 1).get(0), "乘法高于加法");
        assertInstanceOf(JqParser.Binary.class, JqParser.of("1 + 2 * 3").parse());
        assertThrows(IllegalArgumentException.class, () -> JqParser.of(".a ]").parse(), "残词拒绝");
        assertThrows(IllegalArgumentException.class, () -> JqParser.of("( .a").parse(), "括号未闭合");
    }

    @Test
    void pathEvaluationAndOptional() {
        Object input = Map.of("a", Map.of("b", List.of(1, 2, 3)));
        assertEquals(List.of("3"), run(".a.b[2]", input));
        assertEquals(List.of("[1,2,3]"), run(".a.b", input));
        assertEquals(List.of("1", "2", "3"), run(".a.b[]", input));
        assertEquals(List.of("2"), run(".a.b[1]", input), "单索引取元素");
        assertEquals(List.of("null"), run(".a.b[9]", input), "越界为 null");
        assertEquals(List.of("null"), run(".x.y.z", input), "null 链式穿透");
        assertEquals(List.of("2"), run(".a.b[-2]", input), "负数回绕");
        assertEquals(List.of("1", "2", "3"), run(".a | .b[]", input));
        assertEquals(List.of("1", "2"), run(".a.b[] | select(. < 3)", input));
        assertEquals(List.of(), run(".a.b[]?", Map.of("a", Map.of("b", "scalar"))), "optional 错误转空流");
        assertEquals(List.of("null"), run(".a.b[9]?", input), "越界 null 经 ? 仍返 null");
    }

    @Test
    void builtinsSubset() {
        Object input = Map.of("arr", List.of(3, 1, 2), "s", "hello");
        assertEquals(List.of("3"), run(".arr | length", input));
        assertEquals(List.of("hello"), run(".s | tostring", input));
        assertEquals(List.of("[1,2,3]"), run(".arr | sort", input));
        assertEquals(List.of("[2,1,3]"), run(".arr | reverse", input));
        assertEquals(List.of("3"), run(".arr | unique | last", input));
        assertEquals(List.of("3"), run(".arr | first", input));
        assertEquals(List.of("6"), run(".arr | add", input));
        assertEquals(List.of("3-1-2"), run(".arr | join(\"-\")", input));
        assertEquals(List.of("12"), run(".arr | map(. * 2) | add", input));
        assertEquals(List.of("hello"), run(".s | select(startswith(\"he\"))", input));
        assertEquals(List.of(), run(".s | select(endswith(\"he\"))", input));
        assertEquals(List.of("true"), run("{\"a\":1} | has(\"a\")", null));
        assertEquals(List.of("object"), run(". | type", input));
        assertEquals(List.of("42"), run("tonumber", "42"));
        assertTrue(run("keys", Map.of("b", 1, "a", 2)).get(0).contains("a"));
    }

    @Test
    void constructionsAndInterpolation() {
        Object input = Map.of("user", Map.of("name", "chyuan", "age", 30));
        assertEquals(List.of("{name:chyuan,role:admin}"),
                run("{name: .user.name, role: \"admin\"}", input));
        assertEquals(List.of("{n:30}"), run("{n: .user.age}", input));
        assertEquals(List.of("{u:30}"), run("{u: .user.age}", input));
        assertEquals(List.of("chyuan:30"), run("\"\\(.user.name):\\(.user.age)\"", input));
        assertEquals(List.of("[1,2,3,4]"), run("[.[] , 4]", List.of(1, 2, 3)));
        assertEquals(List.of("1", "2"), run("1, 2", null), "逗号多输出");
    }

    @Test
    void reduceForeachAndVariables() {
        assertEquals(List.of("10"), run("[1,2,3,4] | reduce .[] as $x (0; . + $x)", null), "reduce 累加");
        assertEquals(List.of("1", "3", "6", "10"),
                run("[1,2,3,4] | foreach .[] as $x (0; . + $x)", null), "foreach 中间态流");
        assertEquals(List.of("2", "4", "6", "8"),
                run("[1,2,3,4] | foreach .[] as $x (0; . + $x; $x * 2)", null), "foreach 三段式提取");
        assertEquals(List.of("[2,4]"), run("[1,2,3,4] | [.[] | select(. % 2 == 0)]", null));
        assertEquals(List.of("5"), run("2 as $x | 3 as $y | $x + $y", null), "as 变量绑定");
    }

    @Test
    void errorsTryCatchOrderingTruthy() {
        assertEquals(List.of("boom"), run(". | try error catch .", "boom"), "try/catch 捕获");
        assertEquals(List.of(), run("try error catch empty", null));
        assertEquals(List.of("fallback"), run("try (1/0) catch \"fallback\"", null), "除零捕获");

        assertEquals(List.of("false"), run("null and true", null));
        assertEquals(List.of("true"), run("false or true", null));
        assertEquals(List.of("true"), run("false | not", null));
        assertEquals(List.of("true"), run("null == null", null));
        assertEquals(List.of("true"), run("1 < \"a\"", null), "数字 < 字符串定序");
        assertEquals(List.of("true"), run("[1] < {\"a\":1}", null), "数组 < 对象定序");
        assertEquals(List.of("true"), run("\"a\" < [0]", null), "字符串 < 数组定序");
    }
}

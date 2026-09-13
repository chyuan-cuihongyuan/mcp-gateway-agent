package cn.chyuan.ai.domain.policy.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 表达式求值内核（工单 0260 AH1，借鉴 OPA 表达式求值思想）—
 * 零依赖纯函数子集：字面量（字符串/数字/true/false）、属性路径 a.b.c、
 * 比较 == != < <= > >=、逻辑 &amp;&amp; || !（短路）、集合 in {'a','b'}、正则 matches 're'、括号。
 * 求值上下文为根变量 Map（如 subject/object/action/env）。深度与长度超限防护。
 *
 * @author chyuan
 */
public final class ExprKernel {

    /** 表达式最大长度（防爆炸） */
    public static final int MAX_LENGTH = 2048;
    /** AST 最大深度 */
    public static final int MAX_DEPTH = 32;

    private ExprKernel() {
    }

    /** 求值异常（语法/类型/超限统一出口） */
    public static class ExprException extends IllegalArgumentException {
        public ExprException(String message) {
            super(message);
        }
    }

    // ── AST ──
    sealed interface Node permits Lit, Path, Unary, Binary, InNode, Matches {
    }

    record Lit(Object value) implements Node {
    }

    record Path(List<String> segments) implements Node {
    }

    record Unary(String op, Node operand) implements Node {
    }

    record Binary(String op, Node left, Node right) implements Node {
    }

    record InNode(Node operand, Set<Node> candidates) implements Node {
    }

    record Matches(Node operand, Pattern regex) implements Node {
    }

    /**
     * 解析并求值（一次完成；上下文为根变量表，缺失路径求值为 null）。
     * 返回值：Boolean/String/Number/null。
     */
    public static Object evaluate(String expression, Map<String, Object> context) {
        if (expression == null || expression.isBlank()) {
            throw new ExprException("表达式不能为空");
        }
        if (expression.length() > MAX_LENGTH) {
            throw new ExprException("表达式超长（>" + MAX_LENGTH + "）");
        }
        Lexer lexer = new Lexer(expression);
        Parser parser = new Parser(lexer.tokenize());
        Node ast = parser.parseExpression();
        parser.expectEof();
        return evalNode(ast, context, 0);
    }

    /** 布尔便捷求值（非 Boolean 结果抛异常） */
    public static boolean evaluateBoolean(String expression, Map<String, Object> context) {
        Object result = evaluate(expression, context);
        if (!(result instanceof Boolean b)) {
            throw new ExprException("表达式结果非布尔: " + result);
        }
        return b;
    }

    /** 语法预检（不求值） */
    public static void validate(String expression) {
        evaluate(expression, Map.of());
    }

    // ── 词法 ──
    private record Token(String kind, String text) {
    }

    private static final class Lexer {
        private final String src;
        private int pos;

        Lexer(String src) {
            this.src = src;
        }

        List<Token> tokenize() {
            List<Token> tokens = new ArrayList<>();
            while (true) {
                skipWhitespace();
                if (pos >= src.length()) {
                    tokens.add(new Token("EOF", ""));
                    return tokens;
                }
                char c = src.charAt(pos);
                if (Character.isLetter(c)) {
                    String word = readWhile(Character::isLetterOrDigit);
                    switch (word) {
                        case "true", "false" -> tokens.add(new Token("BOOL", word));
                        case "in" -> tokens.add(new Token("OP", "in"));
                        case "matches" -> tokens.add(new Token("OP", "matches"));
                        default -> tokens.add(new Token("IDENT", word));
                    }
                } else if (Character.isDigit(c)) {
                    tokens.add(new Token("NUM", readWhile(ch -> Character.isDigit(ch) || ch == '.')));
                } else if (c == '\'' || c == '"') {
                    tokens.add(new Token("STR", readString(c)));
                } else if (c == '{' || c == '}' || c == '(' || c == ')' || c == '.' || c == ',') {
                    tokens.add(new Token("PUNCT", String.valueOf(c)));
                    pos++;
                } else if (c == '!') {
                    tokens.add(new Token("OP", nextCharEquals('=') ? "!=" : "!"));
                } else if (c == '=') {
                    require(nextCharEquals('='), "非法单 =（相等请用 ==）");
                    tokens.add(new Token("OP", "=="));
                } else if (c == '<' || c == '>') {
                    tokens.add(new Token("OP", nextCharEquals('=') ? String.valueOf(c) + "=" : String.valueOf(c)));
                } else if (c == '&') {
                    require(nextCharEquals('&'), "非法单个 &（逻辑与请用 &&）");
                    tokens.add(new Token("OP", "&&"));
                } else if (c == '|') {
                    require(nextCharEquals('|'), "非法单个 |（逻辑或请用 ||）");
                    tokens.add(new Token("OP", "||"));
                } else {
                    throw new ExprException("非法字符: '" + c + "' @" + pos);
                }
            }
        }

        private void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        private String readWhile(java.util.function.Predicate<Character> predicate) {
            int start = pos;
            while (pos < src.length() && predicate.test(src.charAt(pos))) {
                pos++;
            }
            return src.substring(start, pos);
        }

        private String readString(char quote) {
            pos++;
            int start = pos;
            while (pos < src.length() && src.charAt(pos) != quote) {
                pos++;
            }
            if (pos >= src.length()) {
                throw new ExprException("字符串未闭合 @" + start);
            }
            String value = src.substring(start, pos);
            pos++;
            return value;
        }

        private boolean nextCharEquals(char expected) {
            if (pos + 1 < src.length() && src.charAt(pos + 1) == expected) {
                pos += 2;
                return true;
            }
            return false;
        }

        private void require(boolean condition, String message) {
            if (!condition) {
                throw new ExprException(message);
            }
        }
    }

    // ── 语法（优先级：|| < && < 比较/in/matches < 一元 ! < 路径/字面量） ──
    private static final class Parser {
        private final List<Token> tokens;
        private int index;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        Node parseExpression() {
            return parseOr(0);
        }

        private Node parseOr(int depth) {
            guardDepth(depth);
            Node left = parseAnd(depth);
            while (match("||")) {
                left = new Binary("||", left, parseAnd(depth + 1));
            }
            return left;
        }

        private Node parseAnd(int depth) {
            guardDepth(depth);
            Node left = parseComparison(depth);
            while (match("&&")) {
                left = new Binary("&&", left, parseComparison(depth + 1));
            }
            return left;
        }

        private Node parseComparison(int depth) {
            guardDepth(depth);
            Node left = parseUnary(depth);
            String op = peekOp();
            if (op != null && (op.equals("==") || op.equals("!=") || op.equals("<")
                    || op.equals("<=") || op.equals(">") || op.equals(">="))) {
                advance();
                return new Binary(op, left, parseUnary(depth + 1));
            }
            if (op != null && op.equals("in")) {
                advance();
                return new InNode(left, parseSet(depth + 1));
            }
            if (op != null && op.equals("matches")) {
                advance();
                Node pattern = parseUnary(depth + 1);
                if (!(pattern instanceof Lit lit && lit.value() instanceof String regex)) {
                    throw new ExprException("matches 右侧需为字符串字面量");
                }
                try {
                    return new Matches(left, Pattern.compile(regex));
                } catch (Exception e) {
                    throw new ExprException("matches 非法正则: " + e.getMessage());
                }
            }
            return left;
        }

        private Node parseUnary(int depth) {
            guardDepth(depth);
            if (match("!")) {
                return new Unary("!", parseUnary(depth + 1));
            }
            return parsePrimary(depth + 1);
        }

        private Node parsePrimary(int depth) {
            guardDepth(depth);
            Token token = peek();
            switch (token.kind()) {
                case "NUM" -> {
                    advance();
                    return new Lit(parseNumber(token.text()));
                }
                case "STR" -> {
                    advance();
                    return new Lit(token.text());
                }
                case "BOOL" -> {
                    advance();
                    return new Lit(Boolean.parseBoolean(token.text()));
                }
                case "IDENT" -> {
                    List<String> segments = new ArrayList<>();
                    segments.add(advance().text());
                    while (match(".")) {
                        Token next = peek();
                        if (!"IDENT".equals(next.kind())) {
                            throw new ExprException("路径段需为标识符 @" + next.text());
                        }
                        segments.add(advance().text());
                    }
                    return new Path(List.copyOf(segments));
                }
                case "(" -> {
                    advance();
                    Node inner = parseOr(depth + 1);
                    expect(")");
                    return inner;
                }
                default -> throw new ExprException("意外的记号: " + token.text());
            }
        }

        private Set<Node> parseSet(int depth) {
            expect("{");
            Set<Node> candidates = new HashSet<>();
            if (!match("}")) {
                candidates.add(parseUnary(depth));
                while (match(",")) {
                    candidates.add(parseUnary(depth));
                }
                expect("}");
            }
            return candidates;
        }

        private void expect(String punct) {
            Token token = peek();
            if (!"PUNCT".equals(token.kind()) || !punct.equals(token.text())) {
                throw new ExprException("期望 '" + punct + "'，实际 '" + token.text() + "'");
            }
            advance();
        }

        private void expectEof() {
            Token token = peek();
            if (!"EOF".equals(token.kind())) {
                throw new ExprException("表达式尾部多余内容: " + token.text());
            }
        }

        private boolean match(String punct) {
            Token token = peek();
            if ("PUNCT".equals(token.kind()) && punct.equals(token.text())) {
                advance();
                return true;
            }
            return false;
        }

        private String peekOp() {
            Token token = peek();
            return "OP".equals(token.kind()) ? token.text() : null;
        }

        private Token peek() {
            return tokens.get(Math.min(index, tokens.size() - 1));
        }

        private Token advance() {
            return tokens.get(index < tokens.size() - 1 ? index++ : index);
        }

        private void guardDepth(int depth) {
            if (depth > MAX_DEPTH) {
                throw new ExprException("表达式嵌套过深（>" + MAX_DEPTH + "）");
            }
        }
    }

    private static Number parseNumber(String text) {
        try {
            if (text.contains(".")) {
                return Double.parseDouble(text);
            }
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new ExprException("非法数字: " + text);
        }
    }

    // ── 求值 ──
    private static Object evalNode(Node node, Map<String, Object> context, int depth) {
        if (depth > MAX_DEPTH) {
            throw new ExprException("求值深度超限");
        }
        if (node instanceof Lit lit) {
            return lit.value();
        }
        if (node instanceof Path path) {
            Object current = context;
            for (String segment : path.segments()) {
                if (!(current instanceof Map<?, ?> map)) {
                    return null;
                }
                current = map.get(segment);
            }
            return current;
        }
        if (node instanceof Unary unary) {
            Object value = evalNode(unary.operand(), context, depth + 1);
            if (unary.op().equals("!")) {
                requireBoolean(value, "!");
                return !((Boolean) value);
            }
            throw new ExprException("未知一元操作: " + unary.op());
        }
        if (node instanceof Matches matches) {
            Object value = evalNode(matches.operand(), context, depth + 1);
            return value != null && matches.regex().matcher(String.valueOf(value)).find();
        }
        if (node instanceof InNode in) {
            Object value = evalNode(in.operand(), context, depth + 1);
            for (Node candidate : in.candidates()) {
                if (java.util.Objects.equals(evalNode(candidate, context, depth + 1), value)) {
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        }
        if (node instanceof Binary binary) {
            String op = binary.op();
            if (op.equals("&&") || op.equals("||")) {
                Object left = evalNode(binary.left(), context, depth + 1);
                requireBoolean(left, op);
                if (op.equals("&&")) {
                    return ((Boolean) left) ? requireBooleanNode(binary.right(), context, depth + 1) : Boolean.FALSE;
                }
                return ((Boolean) left) ? Boolean.TRUE : requireBooleanNode(binary.right(), context, depth + 1);
            }
            Object left = evalNode(binary.left(), context, depth + 1);
            Object right = evalNode(binary.right(), context, depth + 1);
            return switch (op) {
                case "==" -> java.util.Objects.equals(left, right);
                case "!=" -> !java.util.Objects.equals(left, right);
                case "<", "<=", ">", ">=" -> compare(left, right, op);
                default -> throw new ExprException("未知操作: " + op);
            };
        }
        throw new ExprException("未知节点");
    }

    private static Boolean requireBooleanNode(Node node, Map<String, Object> context, int depth) {
        Object value = evalNode(node, context, depth);
        requireBoolean(value, "逻辑操作");
        return (Boolean) value;
    }

    private static void requireBoolean(Object value, String op) {
        if (!(value instanceof Boolean)) {
            throw new ExprException("操作 " + op + " 需要布尔操作数，实际: " + value);
        }
    }

    private static Boolean compare(Object left, Object right, String op) {
        if (left instanceof Number ln && right instanceof Number rn) {
            double diff = ln.doubleValue() - rn.doubleValue();
            return switch (op) {
                case "<" -> diff < 0;
                case "<=" -> diff <= 0;
                case ">" -> diff > 0;
                case ">=" -> diff >= 0;
                default -> throw new ExprException("未知比较: " + op);
            };
        }
        if (left instanceof String && right instanceof String) {
            int cmp = String.valueOf(left).compareTo(String.valueOf(right));
            return switch (op) {
                case "<" -> cmp < 0;
                case "<=" -> cmp <= 0;
                case ">" -> cmp > 0;
                case ">=" -> cmp >= 0;
                default -> throw new ExprException("未知比较: " + op);
            };
        }
        throw new ExprException("不可比较的类型: " + left + " vs " + right);
    }
}

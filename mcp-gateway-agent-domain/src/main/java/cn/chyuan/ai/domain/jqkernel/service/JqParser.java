package cn.chyuan.ai.domain.jqkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * jq 解析器（工单 0757 CL2，jq 思想）。
 * 递归下降优先级（管道&lt;逗号&lt;逻辑&lt;比较&lt;加减&lt;乘除&lt;一元&lt;后缀）/
 * 路径后缀链/括号分组/构造与 reduce·foreach·if·try/残词拒绝。
 */
public final class JqParser {

    /** AST 节点 */
    public sealed interface Node permits Identity, Field, Index, Iterate, Pipe, Comma, Literal, VarRef,
            ArrayConstruct, ObjectConstruct, Entry, Interp, FuncCall, Binary, Neg, Reduce, Foreach, If, Try, Catch, AsBind {
    }

    public record AsBind(Node source, String var, Node body) implements Node {
    }

    public record Identity() implements Node {
    }

    public record Field(String name, boolean optional) implements Node {
    }

    public record Index(Node target, Node index, boolean optional) implements Node {
    }

    public record Iterate(Node target, boolean optional) implements Node {
    }

    public record Pipe(Node left, Node right) implements Node {
    }

    public record Comma(Node left, Node right) implements Node {
    }

    public record Literal(Object value) implements Node {
    }

    public record VarRef(String name) implements Node {
    }

    public record ArrayConstruct(Node body) implements Node {
    }

    public record ObjectConstruct(List<Entry> entries) implements Node {
    }

    public record Entry(String key, boolean keyIdent, Node value) implements Node {
    }

    public record Interp(List<Object> parts) implements Node {
    }

    public record FuncCall(String name, List<Node> args) implements Node {
    }

    public record Binary(String op, Node left, Node right) implements Node {
    }

    public record Neg(Node body) implements Node {
    }

    public record Reduce(Node source, String var, Node init, Node update) implements Node {
    }

    public record Foreach(Node source, String var, Node init, Node update, Node extract) implements Node {
    }

    public record If(List<Node[]> branches, Node elseBody) implements Node {
    }

    public record Try(Node body, Node catchBody) implements Node {
    }

    public record Catch() implements Node {
    }

    private final List<JqLexer.Token> tokens;
    private int at;

    public JqParser(List<JqLexer.Token> tokens) {
        this.tokens = tokens;
    }

    public static JqParser of(String source) {
        return new JqParser(new JqLexer(source).lex());
    }

    public Node parse() {
        Node node = parsePipe();
        if (peek().kind() != JqLexer.Kind.EOF) {
            throw new IllegalArgumentException("残词 '" + peek().text() + "'（位 " + peek().position() + "）");
        }
        return node;
    }

    private JqLexer.Token peek() {
        return tokens.get(at);
    }

    private JqLexer.Token next() {
        return tokens.get(at++);
    }

    private boolean match(JqLexer.Kind kind) {
        if (peek().kind() == kind) {
            at++;
            return true;
        }
        return false;
    }

    private JqLexer.Kind peekKind() {
        return peek().kind();
    }

    private Node parsePipe() {
        Node left = parseComma();
        if (match(JqLexer.Kind.AS)) {
            if (peekKind() != JqLexer.Kind.VARIABLE) {
                throw new IllegalArgumentException("as 缺变量");
            }
            String var = next().text();
            match(JqLexer.Kind.PIPE);
            return new AsBind(left, var, parsePipe());
        }
        while (match(JqLexer.Kind.PIPE)) {
            left = new Pipe(left, parseComma());
            if (match(JqLexer.Kind.AS)) {
                if (peekKind() != JqLexer.Kind.VARIABLE) {
                    throw new IllegalArgumentException("as 缺变量");
                }
                String var = next().text();
                match(JqLexer.Kind.PIPE);
                return new AsBind(left, var, parsePipe());
            }
        }
        return left;
    }

    private Node parseComma() {
        Node left = parseLogic();
        while (peekKind() == JqLexer.Kind.COMMA) {
            next();
            left = new Comma(left, parseLogic());
        }
        return left;
    }

    private Node parseLogic() {
        Node left = parseCompare();
        while (peekKind() == JqLexer.Kind.AND || peekKind() == JqLexer.Kind.OR) {
            String op = next().text();
            left = new Binary(op, left, parseCompare());
        }
        return left;
    }

    private Node parseCompare() {
        Node left = parseAdditive();
        JqLexer.Kind kind = peekKind();
        if (kind == JqLexer.Kind.EQ || kind == JqLexer.Kind.NEQ || kind == JqLexer.Kind.LT
                || kind == JqLexer.Kind.LE || kind == JqLexer.Kind.GT || kind == JqLexer.Kind.GE) {
            String op = next().text();
            return new Binary(op, left, parseAdditive());
        }
        return left;
    }

    private Node parseAdditive() {
        Node left = parseMultiplicative();
        while (peekKind() == JqLexer.Kind.PLUS || peekKind() == JqLexer.Kind.MINUS) {
            String op = next().text();
            left = new Binary(op, left, parseMultiplicative());
        }
        return left;
    }

    private Node parseMultiplicative() {
        Node left = parseUnary();
        while (peekKind() == JqLexer.Kind.STAR || peekKind() == JqLexer.Kind.SLASH
                || peekKind() == JqLexer.Kind.PERCENT) {
            String op = next().text();
            left = new Binary(op, left, parseUnary());
        }
        return left;
    }

    private Node parseUnary() {
        if (match(JqLexer.Kind.MINUS)) {
            return new Neg(parsePostfix());
        }
        return parsePostfix();
    }

    private Node parsePostfix() {
        Node node = parsePrimary();
        while (true) {
            if (peekKind() == JqLexer.Kind.DOT && tokens.get(at + 1).kind() == JqLexer.Kind.IDENT) {
                next();
                String name = next().text();
                boolean opt = match(JqLexer.Kind.QUESTION);
                node = chain(node, new Field(name, opt));
            } else if (peekKind() == JqLexer.Kind.LBRACKET) {
                next();
                if (match(JqLexer.Kind.RBRACKET)) {
                    node = chain(node, new Iterate(new Identity(), match(JqLexer.Kind.QUESTION)));
                } else {
                    Node index = parsePipe();
                    if (!match(JqLexer.Kind.RBRACKET)) {
                        throw new IllegalArgumentException("索引缺 ]");
                    }
                    node = chain(node, new Index(new Identity(), index, match(JqLexer.Kind.QUESTION)));
                }
            } else if (peekKind() == JqLexer.Kind.QUESTION) {
                next();
                node = new Try(node, null);
            } else {
                return node;
            }
        }
    }

    /** 后缀路径段链接：目标流经管道进入下一段 */
    private static Node chain(Node target, Node segment) {
        if (target instanceof Identity) {
            return segment;
        }
        return new Pipe(target, segment);
    }

    private Node parsePrimary() {
        JqLexer.Token token = peek();
        switch (token.kind()) {
            case DOT -> {
                next();
                if (peekKind() == JqLexer.Kind.IDENT) {
                    String name = next().text();
                    return new Field(name, match(JqLexer.Kind.QUESTION));
                }
                if (peekKind() == JqLexer.Kind.STRING) {
                    return new Field(next().text(), match(JqLexer.Kind.QUESTION));
                }
                return new Identity();
            }
            case NUMBER -> {
                next();
                return new Literal(Double.parseDouble(token.text()));
            }
            case STRING -> {
                next();
                List<Object> parts = new ArrayList<>();
                if (!token.text().isEmpty()) {
                    parts.add(token.text());
                }
                while (peekKind() == JqLexer.Kind.STRING
                        || (peekKind() == JqLexer.Kind.LPAREN && peek().text().equals("\\("))) {
                    if (peekKind() == JqLexer.Kind.STRING) {
                        parts.add(next().text());
                    } else {
                        next();
                        parts.add(parsePipe());
                        if (!match(JqLexer.Kind.RPAREN)) {
                            throw new IllegalArgumentException("插值缺 )");
                        }
                    }
                }
                if (parts.size() == 1 && parts.get(0) instanceof String s) {
                    return new Literal(s);
                }
                return new Interp(parts);
            }
            case VARIABLE -> {
                next();
                return new VarRef(token.text());
            }
            case LPAREN -> {
                next();
                Node body = parsePipe();
                if (!match(JqLexer.Kind.RPAREN)) {
                    throw new IllegalArgumentException("括号未闭合");
                }
                return body;
            }
            case LBRACKET -> {
                next();
                if (match(JqLexer.Kind.RBRACKET)) {
                    return new ArrayConstruct(null);
                }
                Node body = parsePipe();
                if (!match(JqLexer.Kind.RBRACKET)) {
                    throw new IllegalArgumentException("数组构造缺 ]");
                }
                return new ArrayConstruct(body);
            }
            case LBRACE -> {
                return parseObject();
            }
            case REDUCE -> {
                next();
                Node source = parsePostfix();
                if (!match(JqLexer.Kind.AS)) {
                    throw new IllegalArgumentException("reduce 缺 as");
                }
                if (peekKind() != JqLexer.Kind.VARIABLE) {
                    throw new IllegalArgumentException("reduce 缺变量");
                }
                String var = next().text();
                if (!match(JqLexer.Kind.LPAREN)) {
                    throw new IllegalArgumentException("reduce 缺 (");
                }
                Node init = parsePipe();
                if (!match(JqLexer.Kind.SEMI)) {
                    throw new IllegalArgumentException("reduce 缺 ;");
                }
                Node update = parsePipe();
                if (!match(JqLexer.Kind.RPAREN)) {
                    throw new IllegalArgumentException("reduce 缺 )");
                }
                return new Reduce(source, var, init, update);
            }
            case FOREACH -> {
                next();
                Node source = parsePostfix();
                if (!match(JqLexer.Kind.AS)) {
                    throw new IllegalArgumentException("foreach 缺 as");
                }
                if (peekKind() != JqLexer.Kind.VARIABLE) {
                    throw new IllegalArgumentException("foreach 缺变量");
                }
                String var = next().text();
                if (!match(JqLexer.Kind.LPAREN)) {
                    throw new IllegalArgumentException("foreach 缺 (");
                }
                Node init = parsePipe();
                if (!match(JqLexer.Kind.SEMI)) {
                    throw new IllegalArgumentException("foreach 缺 ;");
                }
                Node update = parsePipe();
                Node extract = null;
                if (match(JqLexer.Kind.SEMI)) {
                    extract = parsePipe();
                }
                if (!match(JqLexer.Kind.RPAREN)) {
                    throw new IllegalArgumentException("foreach 缺 )");
                }
                return new Foreach(source, var, init, update, extract);
            }
            case IF -> {
                next();
                List<Node[]> branches = new ArrayList<>();
                Node cond = parsePipe();
                if (!match(JqLexer.Kind.THEN)) {
                    throw new IllegalArgumentException("if 缺 then");
                }
                branches.add(new Node[]{cond, parsePipe()});
                Node elseBody = new Literal(null);
                while (match(JqLexer.Kind.ELIF)) {
                    Node c = parsePipe();
                    if (!match(JqLexer.Kind.THEN)) {
                        throw new IllegalArgumentException("elif 缺 then");
                    }
                    branches.add(new Node[]{c, parsePipe()});
                }
                if (match(JqLexer.Kind.ELSE)) {
                    elseBody = parsePipe();
                }
                if (!match(JqLexer.Kind.END)) {
                    throw new IllegalArgumentException("if 缺 end");
                }
                return new If(branches, elseBody);
            }
            case TRY -> {
                next();
                Node body = parsePostfix();
                Node catchBody = null;
                if (match(JqLexer.Kind.CATCH)) {
                    catchBody = parsePostfix();
                }
                return new Try(body, catchBody);
            }
            case NOT_KW -> {
                next();
                return new FuncCall("not", List.of());
            }
            case IDENT -> {
                next();
                if (token.text().equals("null") || token.text().equals("true") || token.text().equals("false")) {
                    Object value = token.text().equals("null") ? null
                            : token.text().equals("true") ? Boolean.TRUE : Boolean.FALSE;
                    return new Literal(value);
                }
                List<Node> args = new ArrayList<>();
                if (match(JqLexer.Kind.LPAREN)) {
                    args.add(parsePipe());
                    while (match(JqLexer.Kind.SEMI)) {
                        args.add(parsePipe());
                    }
                    if (!match(JqLexer.Kind.RPAREN)) {
                        throw new IllegalArgumentException("函数调用缺 )");
                    }
                }
                return new FuncCall(token.text(), args);
            }
            default -> throw new IllegalArgumentException("意外记号 '" + token.text() + "'（位 " + token.position() + "）");
        }
    }

    private Node parseObject() {
        if (!match(JqLexer.Kind.LBRACE)) {
            throw new IllegalArgumentException("对象构造缺 {");
        }
        List<Entry> entries = new ArrayList<>();
        if (match(JqLexer.Kind.RBRACE)) {
            return new ObjectConstruct(entries);
        }
        while (true) {
            JqLexer.Token token = peek();
            if (token.kind() == JqLexer.Kind.IDENT) {
                next();
                if (match(JqLexer.Kind.COLON)) {
                    entries.add(new Entry(token.text(), true, parseObjValue()));
                } else {
                    entries.add(new Entry(token.text(), true, new Field(token.text(), false)));
                }
            } else if (token.kind() == JqLexer.Kind.VARIABLE) {
                next();
                String name = token.text();
                entries.add(new Entry(name.substring(1), false, new VarRef(name)));
            } else if (token.kind() == JqLexer.Kind.STRING) {
                next();
                if (!match(JqLexer.Kind.COLON)) {
                    throw new IllegalArgumentException("对象键缺 :");
                }
                entries.add(new Entry(token.text(), false, parseObjValue()));
            } else {
                throw new IllegalArgumentException("非法对象键（位 " + token.position() + "）");
            }
            if (match(JqLexer.Kind.COMMA)) {
                continue;
            }
            if (match(JqLexer.Kind.RBRACE)) {
                return new ObjectConstruct(entries);
            }
            throw new IllegalArgumentException("对象构造缺 }（位 " + peek().position() + "）");
        }
    }

    private Node parseObjValue() {
        return parseLogic();
    }
}

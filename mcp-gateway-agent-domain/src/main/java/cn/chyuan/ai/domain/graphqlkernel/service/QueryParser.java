package cn.chyuan.ai.domain.graphqlkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询解析（工单 0680 CC2，graphql-js 思想）。
 * operation 与选择集/别名与嵌套字段/参数字面量（int/float/string/bool/null/
 * enum/list/object/变量引用）/变量定义与引用/fragment 定义与内联 fragment/
 * 语法错误定位。
 */
public final class QueryParser {

    /** 值：字面量/变量引用 */
    public sealed interface Value {
    }

    public record IntL(long v) implements Value {
    }

    public record FloatL(double v) implements Value {
    }

    public record StrL(String s) implements Value {
    }

    public record BoolL(boolean b) implements Value {
    }

    public record NullL() implements Value {
    }

    public record EnumL(String name) implements Value {
    }

    public record ListL(List<Value> items) implements Value {
    }

    public record ObjectL(Map<String, Value> fields) implements Value {
    }

    public record VarL(String name) implements Value {
    }

    /** 指令：@name(args) */
    public record Directive(String name, Map<String, Value> args) {
    }

    /** 选择：字段（别名/参数/子选择）/fragment spread/内联 fragment */
    public sealed interface Selection {
    }

    public record FieldSel(String alias, String name, Map<String, Value> args,
                           List<Directive> directives, List<Selection> selections) implements Selection {

        public String responseKey() {
            return alias == null ? name : alias;
        }
    }

    public record FragmentSpread(String name, List<Directive> directives) implements Selection {
    }

    public record InlineFragment(String onType, List<Directive> directives, List<Selection> selections) implements Selection {
    }

    public record VarDef(String name, String typeText, Value defaultValue) {
    }

    public record Operation(String name, List<VarDef> varDefs, List<Selection> selections) {
    }

    public record Document(List<Operation> operations, Map<String, List<Selection>> fragments) {

        public Operation soleOperation() {
            if (operations.size() != 1) {
                throw new IllegalArgumentException("文档须恰好一个 operation，实际 " + operations.size());
            }
            return operations.get(0);
        }
    }

    public static Document parse(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("查询文本不得为空");
        }
        Parser parser = new Parser(new Lexer(query).tokenize());
        return parser.parseDocument();
    }

    // ---------------------------------------------------------------- 词法

    private static final class Lexer {
        private final String src;
        private int pos;
        private int line = 1;

        Lexer(String src) {
            this.src = src;
        }

        List<String[]> tokenize() {
            List<String[]> out = new ArrayList<>();
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == '\n') {
                    line++;
                    pos++;
                } else if (Character.isWhitespace(c) || c == ',' || c == '#') {
                    if (c == '#') {
                        while (pos < src.length() && src.charAt(pos) != '\n') {
                            pos++;
                        }
                    } else {
                        pos++;
                    }
                } else if (c == '"') {
                    int start = pos;
                    pos++;
                    while (pos < src.length() && src.charAt(pos) != '"') {
                        if (src.charAt(pos) == '\n') {
                            throw new IllegalArgumentException("第 " + line + " 行字符串未闭合");
                        }
                        pos++;
                    }
                    if (pos >= src.length()) {
                        throw new IllegalArgumentException("第 " + line + " 行字符串未闭合");
                    }
                    pos++;
                    out.add(new String[]{"STRING", src.substring(start + 1, pos - 1), String.valueOf(line)});
                } else if (src.startsWith("...", pos)) {
                    out.add(new String[]{"SPREAD", "...", String.valueOf(line)});
                    pos += 3;
                } else if (c == '$') {
                    out.add(new String[]{"$", "$", String.valueOf(line)});
                    pos++;
                } else if (Character.isDigit(c) || c == '-') {
                    int start = pos;
                    boolean dotted = false;
                    pos++;
                    while (pos < src.length()) {
                        char d = src.charAt(pos);
                        if (Character.isDigit(d)) {
                            pos++;
                        } else if (d == '.' && !dotted && pos + 1 < src.length() && Character.isDigit(src.charAt(pos + 1))) {
                            dotted = true;
                            pos++;
                        } else {
                            break;
                        }
                    }
                    out.add(new String[]{dotted ? "FLOAT" : "INT", src.substring(start, pos), String.valueOf(line)});
                } else if (Character.isJavaIdentifierStart(c) || c == '_') {
                    int start = pos;
                    while (pos < src.length() && (Character.isJavaIdentifierPart(src.charAt(pos)) || src.charAt(pos) == '_')) {
                        pos++;
                    }
                    out.add(new String[]{"NAME", src.substring(start, pos), String.valueOf(line)});
                } else if ("!():=@[]{}|".indexOf(c) >= 0) {
                    out.add(new String[]{String.valueOf(c), String.valueOf(c), String.valueOf(line)});
                    pos++;
                } else {
                    throw new IllegalArgumentException("第 " + line + " 行非法字符 '" + c + "'");
                }
            }
            out.add(new String[]{"EOF", "", String.valueOf(line)});
            return out;
        }
    }

    // ---------------------------------------------------------------- 语法

    private static final class Parser {
        private final List<String[]> tokens;
        private int pos;

        Parser(List<String[]> tokens) {
            this.tokens = tokens;
        }

        Document parseDocument() {
            List<Operation> operations = new ArrayList<>();
            Map<String, List<Selection>> fragments = new LinkedHashMap<>();
            while (!kind().equals("EOF")) {
                if (kind().equals("{")) {
                    expect("{");
                    operations.add(new Operation(null, List.of(), parseSelectionSet()));
                } else if (kind().equals("SPREAD")) {
                    throw new IllegalArgumentException("第 " + line() + " 行匿名 operation 不得以 spread 开头");
                } else if (text().equals("fragment")) {
                    next();
                    String name = expectName();
                    if (!text().equals("on")) {
                        throw new IllegalArgumentException("第 " + line() + " 行 fragment 缺 on 类型条件");
                    }
                    next();
                    String onType = expectName();
                    expect("{");
                    List<Selection> selections = parseSelectionSet();
                    fragments.put(name, selections);
                } else {
                    String opName = expectName();
                    if (!opName.equals("query")) {
                        throw new IllegalArgumentException("第 " + line() + " 行仅支持 query operation：" + opName);
                    }
                    String name = kind().equals("NAME") ? expectName() : null;
                    List<VarDef> varDefs = List.of();
                    if (kind().equals("(")) {
                        next();
                        List<VarDef> defs = new ArrayList<>();
                        while (!kind().equals(")")) {
                            expect("$");
                            String varName = expectName();
                            expect(":");
                            String typeText = parseTypeText();
                            Value defaultValue = null;
                            if (kind().equals("=")) {
                                next();
                                defaultValue = parseValue(false);
                            }
                            defs.add(new VarDef(varName, typeText, defaultValue));
                        }
                        expect(")");
                        varDefs = List.copyOf(defs);
                    }
                    expect("{");
                    operations.add(new Operation(name, varDefs, parseSelectionSet()));
                }
            }
            if (operations.isEmpty()) {
                throw new IllegalArgumentException("文档缺 operation");
            }
            return new Document(List.copyOf(operations), fragments);
        }

        private String parseTypeText() {
            StringBuilder sb = new StringBuilder();
            if (kind().equals("[")) {
                sb.append("[");
                next();
                sb.append(parseTypeText());
                expect("]");
                sb.append("]");
            } else {
                sb.append(expectName());
            }
            if (kind().equals("!")) {
                next();
                sb.append("!");
            }
            return sb.toString();
        }

        private List<Selection> parseSelectionSet() {
            List<Selection> selections = new ArrayList<>();
            while (!kind().equals("}")) {
                if (kind().equals("EOF")) {
                    throw new IllegalArgumentException("第 " + line() + " 行查询截断（缺 }）");
                }
                selections.add(parseSelection());
            }
            next();
            return List.copyOf(selections);
        }

        private Selection parseSelection() {
            if (kind().equals("SPREAD")) {
                next();
                if (kind().equals("NAME") && !text().equals("on")) {
                    String name = expectName();
                    return new FragmentSpread(name, parseDirectives());
                }
                String onType = null;
                if (kind().equals("NAME") && text().equals("on")) {
                    next();
                    onType = expectName();
                }
                expect("{");
                return new InlineFragment(onType, parseDirectives(), parseSelectionSet());
            }
            String name = expectName();
            String alias = null;
            if (kind().equals(":")) {
                next();
                alias = name;
                name = expectName();
            }
            Map<String, Value> args = Map.of();
            if (kind().equals("(")) {
                next();
                Map<String, Value> map = new LinkedHashMap<>();
                while (!kind().equals(")")) {
                    String argName = expectName();
                    expect(":");
                    map.put(argName, parseValue(true));
                }
                expect(")");
                args = Map.copyOf(map);
            }
            List<Directive> directives = parseDirectives();
            List<Selection> selections = List.of();
            if (kind().equals("{")) {
                next();
                selections = parseSelectionSet();
            }
            return new FieldSel(alias, name, args, directives, selections);
        }

        private List<Directive> parseDirectives() {
            List<Directive> out = new ArrayList<>();
            while (kind().equals("@")) {
                next();
                String name = expectName();
                Map<String, Value> args = Map.of();
                if (kind().equals("(")) {
                    next();
                    Map<String, Value> map = new LinkedHashMap<>();
                    while (!kind().equals(")")) {
                        String argName = expectName();
                        expect(":");
                        map.put(argName, parseValue(true));
                    }
                    expect(")");
                    args = Map.copyOf(map);
                }
                out.add(new Directive(name, args));
            }
            return List.copyOf(out);
        }

        private Value parseValue(boolean allowVar) {
            String k = kind();
            switch (k) {
                case "INT": {
                    long v = Long.parseLong(text());
                    next();
                    return new IntL(v);
                }
                case "FLOAT": {
                    double v = Double.parseDouble(text());
                    next();
                    return new FloatL(v);
                }
                case "STRING": {
                    String s = text();
                    next();
                    return new StrL(s);
                }
                case "$": {
                    if (!allowVar) {
                        throw new IllegalArgumentException("第 " + line() + " 行此处不允许变量");
                    }
                    next();
                    return new VarL(expectName());
                }
                case "[": {
                    next();
                    List<Value> items = new ArrayList<>();
                    while (!kind().equals("]")) {
                        items.add(parseValue(allowVar));
                        if (kind().equals(",")) {
                            next();
                        }
                    }
                    expect("]");
                    return new ListL(List.copyOf(items));
                }
                case "{": {
                    next();
                    Map<String, Value> fields = new LinkedHashMap<>();
                    while (!kind().equals("}")) {
                        String fieldName = expectName();
                        expect(":");
                        fields.put(fieldName, parseValue(allowVar));
                    }
                    expect("}");
                    return new ObjectL(Map.copyOf(fields));
                }
                case "NAME": {
                    String name = expectName();
                    if (name.equals("true")) {
                        return new BoolL(true);
                    }
                    if (name.equals("false")) {
                        return new BoolL(false);
                    }
                    if (name.equals("null")) {
                        return new NullL();
                    }
                    return new EnumL(name);
                }
                default:
                    throw new IllegalArgumentException("第 " + line() + " 行意外的值记号 " + k);
            }
        }

        private String kind() {
            return tokens.get(pos)[0];
        }

        private String text() {
            return tokens.get(pos)[1];
        }

        private int line() {
            return Integer.parseInt(tokens.get(pos)[2]);
        }

        private String next() {
            return tokens.get(pos++)[0];
        }

        private String expectName() {
            if (!kind().equals("NAME")) {
                throw new IllegalArgumentException("第 " + line() + " 行期望名称实为 " + kind());
            }
            String name = text();
            next();
            return name;
        }

        private void expect(String kind) {
            if (!kind().equals(kind)) {
                throw new IllegalArgumentException("第 " + line() + " 行期望 " + kind + " 实为 " + kind());
            }
            next();
        }
    }
}

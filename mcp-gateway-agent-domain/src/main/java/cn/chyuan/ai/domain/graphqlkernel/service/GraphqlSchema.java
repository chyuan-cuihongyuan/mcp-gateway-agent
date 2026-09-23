package cn.chyuan.ai.domain.graphqlkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SDL Schema 解析（工单 0679 CC1，graphql-js 思想）。
 * type/input/enum/scalar 定义/字段类型 Named·List·NonNull 修饰递归/
 * 注释 # 忽略/语法错误按记号定位/重名类型拒绝/内省结构化输出。
 */
public final class GraphqlSchema {

    public enum Kind { OBJECT, INPUT, ENUM, SCALAR }

    /** 类型引用：Named / List(ofType) / NonNull(ofType) */
    public sealed interface TypeRef permits Named, ListT, NonNullT {
    }

    public record Named(String name) implements TypeRef {
    }

    public record ListT(TypeRef ofType) implements TypeRef {
    }

    public record NonNullT(TypeRef ofType) implements TypeRef {
    }

    public record Arg(String name, TypeRef type) {
    }

    public record FieldDef(String name, TypeRef type, List<Arg> args) {
    }

    public record TypeDef(Kind kind, String name, Map<String, FieldDef> fields, List<String> enumValues) {
    }

    public static final List<String> BUILTIN_SCALARS = List.of("Int", "Float", "String", "Boolean", "ID");

    private final Map<String, TypeDef> types = new LinkedHashMap<>();

    public static GraphqlSchema parse(String sdl) {
        if (sdl == null || sdl.isBlank()) {
            throw new IllegalArgumentException("SDL 不得为空");
        }
        GraphqlSchema schema = new GraphqlSchema();
        for (String scalar : BUILTIN_SCALARS) {
            schema.types.put(scalar, new TypeDef(Kind.SCALAR, scalar, Map.of(), List.of()));
        }
        Parser parser = new Parser(new Lexer(sdl).tokenize());
        parser.parseDocument(schema);
        return schema;
    }

    public TypeDef type(String name) {
        return types.get(name);
    }

    public List<TypeDef> allTypes() {
        return List.copyOf(types.values());
    }

    /** 按字段求值对象类型（Non/List 拆包） */
    public static TypeRef unwrap(TypeRef ref) {
        if (ref instanceof NonNullT n) {
            return unwrap(n.ofType());
        }
        if (ref instanceof ListT l) {
            return unwrap(l.ofType());
        }
        return ref;
    }

    public String typeQualifiedName(TypeRef ref) {
        if (ref instanceof Named n) {
            return n.name();
        }
        if (ref instanceof NonNullT n) {
            return typeQualifiedName(n.ofType()) + "!";
        }
        return "[" + typeQualifiedName(((ListT) ref).ofType()) + "]";
    }

    /** 最小内省：__schema 顶层结构化输出（executor 按选择集走 Map） */
    public Map<String, Object> introspectionSchema() {
        String queryType = types.containsKey("Query") ? "Query" : null;
        List<Map<String, Object>> typeList = new ArrayList<>();
        for (TypeDef def : types.values()) {
            typeList.add(typeMap(def));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> queryTypeMap = new LinkedHashMap<>();
        queryTypeMap.put("name", queryType);
        out.put("queryType", queryTypeMap);
        out.put("types", typeList);
        return out;
    }

    /** 最小内省：__type(name:) 输出 */
    public Map<String, Object> introspectionType(String name) {
        TypeDef def = types.get(name);
        return def == null ? null : typeMap(def);
    }

    private Map<String, Object> typeMap(TypeDef def) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", def.kind().name());
        out.put("name", def.name());
        List<Map<String, Object>> fields = new ArrayList<>();
        for (FieldDef field : def.fields().values()) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("name", field.name());
            fm.put("type", typeRefMap(field.type()));
            fields.add(fm);
        }
        out.put("fields", fields);
        out.put("enumValues", def.enumValues());
        return out;
    }

    private Map<String, Object> typeRefMap(TypeRef ref) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (ref instanceof Named n) {
            out.put("kind", "SCALAR");
            out.put("name", n.name());
            out.put("ofType", null);
        } else if (ref instanceof NonNullT n) {
            out.put("kind", "NON_NULL");
            out.put("name", null);
            out.put("ofType", typeRefMap(n.ofType()));
        } else {
            out.put("kind", "LIST");
            out.put("name", null);
            out.put("ofType", typeRefMap(((ListT) ref).ofType()));
        }
        return out;
    }

    // ---------------------------------------------------------------- 词法

    private static final class Lexer {
        private final String src;
        private int pos;
        private int line = 1;

        Lexer(String src) {
            this.src = src == null ? "" : src;
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
                        pos++;
                    }
                    if (pos >= src.length()) {
                        throw new IllegalArgumentException("第 " + line + " 行字符串未闭合");
                    }
                    pos++;
                    out.add(new String[]{"STRING", src.substring(start + 1, pos - 1), String.valueOf(line)});
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
                } else if ("!$():=@[]{}|".indexOf(c) >= 0) {
                    out.add(new String[]{String.valueOf(c), String.valueOf(c), String.valueOf(line)});
                    pos++;
                } else if (c == '.') {
                    if (src.startsWith("...", pos)) {
                        out.add(new String[]{"SPREAD", "...", String.valueOf(line)});
                        pos += 3;
                    } else {
                        throw new IllegalArgumentException("第 " + line + " 行非法字符 '.'");
                    }
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

        void parseDocument(GraphqlSchema schema) {
            while (!kind().equals("EOF")) {
                String name = text();
                switch (name) {
                    case "type", "input" -> parseObjectLike(schema, name.equals("type") ? Kind.OBJECT : Kind.INPUT);
                    case "enum" -> parseEnum(schema);
                    case "scalar" -> {
                        next();
                        String scalarName = expectName();
                        if (schema.types.containsKey(scalarName)) {
                            throw new IllegalArgumentException("第 " + line() + " 行重名类型：" + scalarName);
                        }
                        schema.types.put(scalarName, new TypeDef(Kind.SCALAR, scalarName, Map.of(), List.of()));
                    }
                    default -> throw new IllegalArgumentException("第 " + line() + " 行不支持的 SDL 定义：" + name);
                }
            }
        }

        private void parseObjectLike(GraphqlSchema schema, Kind kind) {
            next();
            String typeName = expectName();
            if (schema.types.containsKey(typeName)) {
                throw new IllegalArgumentException("第 " + line() + " 行重名类型：" + typeName);
            }
            expect("{");
            Map<String, FieldDef> fields = new LinkedHashMap<>();
            while (!kind().equals("}")) {
                if (kind().equals("EOF")) {
                    throw new IllegalArgumentException("第 " + line() + " 行 Schema 截断（缺 }）");
                }
                String fieldName = expectName();
                List<Arg> args = List.of();
                if (kind().equals("(")) {
                    next();
                    List<Arg> argList = new ArrayList<>();
                    while (!kind().equals(")")) {
                        String argName = expectName();
                        expect(":");
                        argList.add(new Arg(argName, parseTypeRef()));
                        if (kind().equals("=")) {
                            next();
                            skipValue();
                        }
                    }
                    expect(")");
                    args = List.copyOf(argList);
                }
                expect(":");
                fields.put(fieldName, new FieldDef(fieldName, parseTypeRef(), args));
            }
            next();
            schema.types.put(typeName, new TypeDef(kind, typeName, fields, List.of()));
        }

        private void parseEnum(GraphqlSchema schema) {
            next();
            String typeName = expectName();
            if (schema.types.containsKey(typeName)) {
                throw new IllegalArgumentException("第 " + line() + " 行重名类型：" + typeName);
            }
            expect("{");
            List<String> values = new ArrayList<>();
            while (!kind().equals("}")) {
                values.add(expectName());
            }
            next();
            schema.types.put(typeName, new TypeDef(Kind.ENUM, typeName, Map.of(), List.copyOf(values)));
        }

        private TypeRef parseTypeRef() {
            TypeRef ref;
            if (kind().equals("[")) {
                next();
                TypeRef inner = parseTypeRef();
                expect("]");
                ref = new ListT(inner);
            } else {
                ref = new Named(expectName());
            }
            if (kind().equals("!")) {
                next();
                ref = new NonNullT(ref);
            }
            return ref;
        }

        private void skipValue() {
            if (kind().equals("[")) {
                int depth = 0;
                while (!kind().equals("]") || depth > 0) {
                    if (kind().equals("[")) {
                        depth++;
                    }
                    if (kind().equals("]")) {
                        depth--;
                    }
                    next();
                }
                next();
                return;
            }
            next();
            if (kind().equals("!") || kind().equals(".")) {
                // 容错：跳过可能跟随的记号
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

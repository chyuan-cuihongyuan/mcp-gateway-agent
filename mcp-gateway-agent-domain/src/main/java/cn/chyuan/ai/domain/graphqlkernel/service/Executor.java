package cn.chyuan.ai.domain.graphqlkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行器（工单 0682-0685·0687 CC4-CC5·CC7，graphql-js 思想）。
 * 根 resolver 注入与字段解析链（父值+参数→子值，默认按 Map 取值）/
 * 列表源逐项解析/__typename 内建字段/变量 coercion（默认值+非空校验）/
 * skip·include 指令/错误规范化（message+path，部分数据与错误并存，
 * 字段失败置 null 不中断兄弟字段）。
 */
public final class Executor {

    /** 规范化错误：message+响应路径 */
    public record GqlError(String message, List<Object> path) {
    }

    public record ExecutionResult(Map<String, Object> data, List<GqlError> errors) {

        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }

    /** 字段解析器：parent 父值/args 已求值参数/variables 已 coerce 变量 */
    public interface FieldResolver {
        Object resolve(Object parent, Map<String, Object> args, Map<String, Object> variables);
    }

    private final GraphqlSchema schema;
    private final Map<String, FieldResolver> resolvers;

    public Executor(GraphqlSchema schema, Map<String, FieldResolver> resolvers) {
        this.schema = schema;
        this.resolvers = resolvers == null ? Map.of() : Map.copyOf(resolvers);
    }

    public ExecutionResult execute(QueryParser.Document document, Map<String, Object> variables) {
        QueryParser.Operation operation = document.soleOperation();
        Map<String, Object> vars = coerceVariables(operation.varDefs(), variables == null ? Map.of() : variables);
        Map<String, Object> data = new LinkedHashMap<>();
        List<GqlError> errors = new ArrayList<>();
        walk(operation.selections(), Validator.QUERY_TYPE, Map.of(), data, errors,
                List.of(), vars, document.fragments());
        return new ExecutionResult(data, List.copyOf(errors));
    }

    private void walk(List<QueryParser.Selection> selections, String typeName, Object parentValue,
                      Map<String, Object> out, List<GqlError> errors, List<Object> path,
                      Map<String, Object> vars, Map<String, List<QueryParser.Selection>> fragments) {
        for (QueryParser.Selection selection : selections) {
            if (selection instanceof QueryParser.InlineFragment inline) {
                if (visible(inline.directives(), vars)) {
                    walk(inline.selections(), typeName, parentValue, out, errors, path, vars, fragments);
                }
                continue;
            }
            if (selection instanceof QueryParser.FragmentSpread spread) {
                if (visible(spread.directives(), vars)) {
                    walk(fragments.get(spread.name()), typeName, parentValue, out, errors, path, vars, fragments);
                }
                continue;
            }
            QueryParser.FieldSel field = (QueryParser.FieldSel) selection;
            if (!visible(field.directives(), vars)) {
                continue;
            }
            String key = field.responseKey();
            if (field.name().equals("__typename")) {
                out.put(key, typeName);
                continue;
            }
            if (field.name().equals("__schema")) {
                Map<String, Object> child = new LinkedHashMap<>();
                walk(field.selections(), "__introspection", schema.introspectionSchema(),
                        child, errors, append(path, key), vars, fragments);
                out.put(key, child);
                continue;
            }
            if (field.name().equals("__type")) {
                String name = String.valueOf(evalValue(field.args().get("name"), vars));
                Map<String, Object> child = new LinkedHashMap<>();
                walk(field.selections(), "__introspection", schema.introspectionType(name),
                        child, errors, append(path, key), vars, fragments);
                out.put(key, child);
                continue;
            }
            Map<String, Object> args = evalArgs(field.args(), vars);
            Object value;
            try {
                FieldResolver resolver = resolvers.get(typeName + "." + field.name());
                value = resolver != null
                        ? resolver.resolve(parentValue, args, vars)
                        : defaultValue(parentValue, field.name());
            } catch (RuntimeException e) {
                errors.add(new GqlError("字段 " + key + " 解析失败：" + e.getMessage(), append(path, key)));
                out.put(key, null);
                continue;
            }
            fill(field, value, out, key, typeName, errors, path, vars, fragments);
        }
    }

    private void fill(QueryParser.FieldSel field, Object value, Map<String, Object> out, String key,
                      String typeName, List<GqlError> errors, List<Object> path,
                      Map<String, Object> vars, Map<String, List<QueryParser.Selection>> fragments) {
        if (field.selections().isEmpty()) {
            out.put(key, value);
            return;
        }
        String childType = childTypeOf(typeName, field.name());
        if (value instanceof List<?> list) {
            List<Object> items = new ArrayList<>();
            for (Object item : list) {
                Map<String, Object> child = new LinkedHashMap<>();
                walk(field.selections(), childType, item, child, errors, append(path, key), vars, fragments);
                items.add(child);
            }
            out.put(key, items);
            return;
        }
        Map<String, Object> child = new LinkedHashMap<>();
        walk(field.selections(), childType, value, child, errors, append(path, key), vars, fragments);
        out.put(key, child);
    }

    private String childTypeOf(String typeName, String fieldName) {
        if (typeName.startsWith("__")) {
            return "__introspection";
        }
        GraphqlSchema.TypeDef type = schema.type(typeName);
        if (type == null) {
            return "__introspection";
        }
        GraphqlSchema.FieldDef def = type.fields().get(fieldName);
        if (def == null) {
            return "__introspection";
        }
        GraphqlSchema.TypeRef unwrapped = GraphqlSchema.unwrap(def.type());
        return ((GraphqlSchema.Named) unwrapped).name();
    }

    private static Object defaultValue(Object parent, String name) {
        if (parent instanceof Map<?, ?> map) {
            return map.get(name);
        }
        return null;
    }

    private Map<String, Object> evalArgs(Map<String, QueryParser.Value> args, Map<String, Object> vars) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, QueryParser.Value> e : args.entrySet()) {
            out.put(e.getKey(), evalValue(e.getValue(), vars));
        }
        return out;
    }

    /** 变量与字面量求值（变量已 coerce，缺省 null） */
    public static Object evalValue(QueryParser.Value value, Map<String, Object> vars) {
        if (value instanceof QueryParser.IntL v) {
            return v.v();
        }
        if (value instanceof QueryParser.FloatL v) {
            return v.v();
        }
        if (value instanceof QueryParser.StrL v) {
            return v.s();
        }
        if (value instanceof QueryParser.BoolL v) {
            return v.b();
        }
        if (value instanceof QueryParser.NullL) {
            return null;
        }
        if (value instanceof QueryParser.EnumL v) {
            return v.name();
        }
        if (value instanceof QueryParser.VarL v) {
            return vars.get(v.name());
        }
        if (value instanceof QueryParser.ListL list) {
            List<Object> out = new ArrayList<>();
            for (QueryParser.Value item : list.items()) {
                out.add(evalValue(item, vars));
            }
            return out;
        }
        if (value instanceof QueryParser.ObjectL object) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, QueryParser.Value> e : object.fields().entrySet()) {
                out.put(e.getKey(), evalValue(e.getValue(), vars));
            }
            return out;
        }
        return null;
    }

    /** 变量 coercion：默认值回填/非空缺失拒绝/标量类型适配 */
    static Map<String, Object> coerceVariables(List<QueryParser.VarDef> varDefs, Map<String, Object> provided) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (QueryParser.VarDef def : varDefs) {
            Object value = provided.get(def.name());
            if (value == null && def.defaultValue() != null) {
                value = evalValue(def.defaultValue(), Map.of());
            }
            boolean nonNull = def.typeText().endsWith("!");
            if (value == null) {
                if (nonNull) {
                    throw new IllegalArgumentException("变量 $" + def.name() + " 非空必填未提供");
                }
                out.put(def.name(), null);
                continue;
            }
            String base = def.typeText().replace("[", "").replace("]", "").replace("!", "");
            out.put(def.name(), coerceScalar(base, value, def.name()));
        }
        return out;
    }

    private static Object coerceScalar(String base, Object value, String name) {
        boolean isList = false;
        return switch (base) {
            case "Int" -> {
                if (value instanceof Number n && n.longValue() == n.doubleValue()) {
                    yield n.longValue();
                }
                yield fail(name, "Int");
            }
            case "Float" -> {
                if (value instanceof Number n) {
                    yield n.doubleValue();
                }
                yield fail(name, "Float");
            }
            case "String", "ID" -> {
                if (value instanceof String s) {
                    yield s;
                }
                yield fail(name, base);
            }
            case "Boolean" -> {
                if (value instanceof Boolean b) {
                    yield b;
                }
                yield fail(name, "Boolean");
            }
            default -> value;
        };
    }

    private static Object fail(String name, String type) {
        throw new IllegalArgumentException("变量 $" + name + " 类型须为 " + type);
    }

    private static boolean visible(List<QueryParser.Directive> directives, Map<String, Object> vars) {
        for (QueryParser.Directive directive : directives) {
            Object arg = evalValue(directive.args().get("if"), vars);
            boolean condition = Boolean.TRUE.equals(arg);
            if (directive.name().equals("skip") && condition) {
                return false;
            }
            if (directive.name().equals("include") && !condition) {
                return false;
            }
        }
        return true;
    }

    private static List<Object> append(List<Object> path, Object segment) {
        List<Object> out = new ArrayList<>(path);
        out.add(segment);
        return out;
    }
}

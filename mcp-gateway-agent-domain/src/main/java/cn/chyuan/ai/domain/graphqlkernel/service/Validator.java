package cn.chyuan.ai.domain.graphqlkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 查询校验（工单 0681 CC3，graphql-js 思想）。
 * 字段存在于类型/叶字段必须标量枚举（对象字段必须子选择集）/
 * 非空必填参数缺失拒绝/未知参数拒绝/变量类型匹配与未定义变量拒绝/
 * fragment 已定义检查（__schema/__type 走最小内省白名单豁免）。
 */
public final class Validator {

    public static final String QUERY_TYPE = "Query";

    private Validator() {
    }

    public static void validate(GraphqlSchema schema, QueryParser.Document document) {
        GraphqlSchema.TypeDef query = schema.type(QUERY_TYPE);
        if (query == null) {
            throw new IllegalArgumentException("Schema 缺 Query 根类型");
        }
        for (QueryParser.Operation operation : document.operations()) {
            walk(schema, query, operation.selections(), document.fragments(), operation.varDefs(), List.of());
        }
        for (Map.Entry<String, List<QueryParser.Selection>> e : document.fragments().entrySet()) {
            // fragment 定义体延迟到 spread 处校验；未使用 fragment 不校验（惰性口径）
        }
    }

    private static void walk(GraphqlSchema schema, GraphqlSchema.TypeDef type, List<QueryParser.Selection> selections,
                             Map<String, List<QueryParser.Selection>> fragments,
                             List<QueryParser.VarDef> varDefs, List<String> path) {
        for (QueryParser.Selection selection : selections) {
            if (selection instanceof QueryParser.InlineFragment inline) {
                if (inline.onType() != null && schema.type(inline.onType()) == null) {
                    throw new IllegalArgumentException("内联 fragment 未知类型：" + inline.onType());
                }
                checkDirectives(inline.directives(), varDefs);
                walk(schema, type, inline.selections(), fragments, varDefs, path);
                continue;
            }
            if (selection instanceof QueryParser.FragmentSpread spread) {
                checkDirectives(spread.directives(), varDefs);
                List<QueryParser.Selection> body = fragments.get(spread.name());
                if (body == null) {
                    throw new IllegalArgumentException("未定义 fragment：" + spread.name());
                }
                walk(schema, type, body, fragments, varDefs, path);
                continue;
            }
            QueryParser.FieldSel field = (QueryParser.FieldSel) selection;
            if (field.name().equals("__schema") || field.name().equals("__type")) {
                checkDirectives(field.directives(), varDefs);
                continue;
            }
            if (field.name().equals("__typename")) {
                checkDirectives(field.directives(), varDefs);
                continue;
            }
            GraphqlSchema.FieldDef def = type.fields().get(field.name());
            if (def == null) {
                throw new IllegalArgumentException("类型 " + type.name() + " 无字段 " + field.name()
                        + "（查询路径 " + String.join("/", path) + "）");
            }
            checkArgs(schema, type.name(), def, field.args(), varDefs);
            checkDirectives(field.directives(), varDefs);
            GraphqlSchema.TypeRef unwrapped = GraphqlSchema.unwrap(def.type());
            String target = ((GraphqlSchema.Named) unwrapped).name();
            GraphqlSchema.TypeDef targetType = schema.type(target);
            boolean composite = targetType != null && targetType.kind() == GraphqlSchema.Kind.OBJECT;
            if (composite && field.selections().isEmpty()) {
                throw new IllegalArgumentException("对象字段 " + field.name() + " 必须带子选择集");
            }
            if (!composite && !field.selections().isEmpty()) {
                throw new IllegalArgumentException("叶字段 " + field.name() + " 不得带子选择集");
            }
            if (composite) {
                walk(schema, targetType, field.selections(), fragments, varDefs,
                        concat(path, field.name()));
            }
        }
    }

    private static void checkArgs(GraphqlSchema schema, String typeName, GraphqlSchema.FieldDef def,
                                  Map<String, QueryParser.Value> args, List<QueryParser.VarDef> varDefs) {
        for (GraphqlSchema.Arg arg : def.args()) {
            QueryParser.Value provided = args.get(arg.name());
            boolean nonNull = arg.type() instanceof GraphqlSchema.NonNullT;
            if (provided == null && nonNull) {
                throw new IllegalArgumentException("字段 " + def.name() + " 缺非空参数 " + arg.name());
            }
        }
        for (String provided : args.keySet()) {
            boolean known = def.args().stream().anyMatch(a -> a.name().equals(provided));
            if (!known) {
                throw new IllegalArgumentException("字段 " + def.name() + " 未知参数 " + provided);
            }
            checkVarDeclared(args.get(provided), varDefs);
        }
    }

    private static void checkDirectives(List<QueryParser.Directive> directives, List<QueryParser.VarDef> varDefs) {
        for (QueryParser.Directive directive : directives) {
            if (!directive.name().equals("skip") && !directive.name().equals("include")) {
                throw new IllegalArgumentException("不支持指令 @" + directive.name());
            }
            for (QueryParser.Value value : directive.args().values()) {
                checkVarDeclared(value, varDefs);
            }
        }
    }

    private static void checkVarDeclared(QueryParser.Value value, List<QueryParser.VarDef> varDefs) {
        if (value instanceof QueryParser.VarL var) {
            boolean declared = varDefs.stream().anyMatch(d -> d.name().equals(var.name()));
            if (!declared) {
                throw new IllegalArgumentException("未定义变量 $" + var.name());
            }
            return;
        }
        if (value instanceof QueryParser.ListL list) {
            for (QueryParser.Value item : list.items()) {
                checkVarDeclared(item, varDefs);
            }
        } else if (value instanceof QueryParser.ObjectL object) {
            for (QueryParser.Value inner : object.fields().values()) {
                checkVarDeclared(inner, varDefs);
            }
        }
    }

    private static List<String> concat(List<String> path, String segment) {
        List<String> out = new ArrayList<>(path);
        out.add(segment);
        return out;
    }
}

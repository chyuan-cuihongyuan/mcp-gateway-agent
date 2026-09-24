package cn.chyuan.ai.domain.jqkernel.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * jq 求值器（工单 0758-0762 CL3-CL7，jq 思想）。
 * 路径求值（字段/索引负回绕/迭代/optional 空流）/内建函数子集/构造与插值/
 * reduce·foreach·as/try·catch/错误流与 truthy/跨类型比较定序。
 */
public final class JqEvaluator {

    /** jq 运行时错误 */
    public static final class JqError extends RuntimeException {
        public JqError(String message) {
            super(message);
        }
    }

    private record Env(Map<String, Object> vars, Env parent) {
        Object lookup(String name) {
            Env env = this;
            while (env != null) {
                if (env.vars.containsKey(name)) {
                    return env.vars.get(name);
                }
                env = env.parent;
            }
            throw new JqError("未绑定变量: $" + name);
        }
    }

    private final JqParser.Node program;

    public JqEvaluator(JqParser.Node program) {
        this.program = program;
    }

    /** 求值单输入 → 输出流 */
    public List<Object> run(Object input) {
        return eval(program, input, new Env(new HashMap<>(), null));
    }

    private List<Object> eval(JqParser.Node node, Object input, Env env) {
        if (node instanceof JqParser.Identity) {
            return single(input);
        }
        if (node instanceof JqParser.Literal lit) {
            return single(lit.value());
        }
        if (node instanceof JqParser.VarRef var) {
            return single(env.lookup(var.name()));
        }
        if (node instanceof JqParser.Pipe pipe) {
            List<Object> out = new ArrayList<>();
            for (Object mid : eval(pipe.left(), input, env)) {
                out.addAll(eval(pipe.right(), mid, env));
            }
            return out;
        }
        if (node instanceof JqParser.Comma comma) {
            List<Object> out = new ArrayList<>(eval(comma.left(), input, env));
            out.addAll(eval(comma.right(), input, env));
            return out;
        }
        if (node instanceof JqParser.Field field) {
            try {
                return single(fieldAccess(input, field.name()));
            } catch (JqError e) {
                if (field.optional()) {
                    return single();
                }
                throw e;
            }
        }
        if (node instanceof JqParser.Index index) {
            List<Object> out = new ArrayList<>();
            for (Object targetValue : eval(index.target(), input, env)) {
                for (Object key : eval(index.index(), input, env)) {
                    try {
                        out.add(indexAccess(targetValue, key));
                    } catch (JqError e) {
                        if (index.optional()) {
                            return single();
                        }
                        throw e;
                    }
                }
            }
            return out;
        }
        if (node instanceof JqParser.Iterate iterate) {
            List<Object> out = new ArrayList<>();
            try {
                if (input instanceof List<?> list) {
                    out.addAll(list);
                } else if (input instanceof Map<?, ?> map) {
                    out.addAll(map.values());
                } else {
                    throw new JqError("不能迭代 " + JqValue.typeName(input));
                }
            } catch (JqError e) {
                if (iterate.optional()) {
                    return single();
                }
                throw e;
            }
            return out;
        }
        if (node instanceof JqParser.ArrayConstruct array) {
            return single(array.body() == null ? single() : List.copyOf(eval(array.body(), input, env)));
        }
        if (node instanceof JqParser.ObjectConstruct object) {
            List<Object> out = single((Object) buildObject(object.entries(), 0, JqValue.object(), input, env));
            return out;
        }
        if (node instanceof JqParser.Interp interp) {
            StringBuilder sb = new StringBuilder();
            for (Object part : interp.parts()) {
                if (part instanceof String s) {
                    sb.append(s);
                } else {
                    for (Object out : eval((JqParser.Node) part, input, env)) {
                        sb.append(stringify(out));
                    }
                }
            }
            return single(sb.toString());
        }
        if (node instanceof JqParser.FuncCall call) {
            return callBuiltin(call.name(), call.args(), input, env);
        }
        if (node instanceof JqParser.Binary binary) {
            return evalBinary(binary, input, env);
        }
        if (node instanceof JqParser.Neg neg) {
            List<Object> out = new ArrayList<>();
            for (Object v : eval(neg.body(), input, env)) {
                if (!(v instanceof Number n)) {
                    throw new JqError("负号需数字: " + JqValue.typeName(v));
                }
                out.add(-n.doubleValue());
            }
            return out;
        }
        if (node instanceof JqParser.AsBind asBind) {
            List<Object> out = new ArrayList<>();
            for (Object value : eval(asBind.source(), input, env)) {
                Map<String, Object> vars = new HashMap<>();
                vars.put(asBind.var(), value);
                out.addAll(eval(asBind.body(), input, new Env(vars, env)));
            }
            return out;
        }
        if (node instanceof JqParser.Reduce reduce) {
            return single(evalReduce(reduce, input, env));
        }
        if (node instanceof JqParser.Foreach foreach) {
            return evalForeach(foreach, input, env);
        }
        if (node instanceof JqParser.If ifNode) {
            List<Object> out = new ArrayList<>();
            evalIf(ifNode, 0, input, env, out);
            return out;
        }
        if (node instanceof JqParser.Try tryNode) {
            try {
                return eval(tryNode.body(), input, env);
            } catch (JqError e) {
                if (tryNode.catchBody() != null) {
                    return eval(tryNode.catchBody(), e.getMessage(), env);
                }
                return single();
            }
        }
        if (node instanceof JqParser.Catch) {
            return single(input);
        }
        throw new IllegalStateException("未知节点: " + node.getClass());
    }

    private Object fieldAccess(Object input, String name) {
        if (input == null) {
            return null;
        }
        if (input instanceof Map<?, ?> map) {
            return map.get(name);
        }
        throw new JqError("字段访问需对象: " + JqValue.typeName(input));
    }

    @SuppressWarnings("unchecked")
    private Object indexAccess(Object input, Object key) {
        if (key instanceof String s) {
            return fieldAccess(input, s);
        }
        if (key instanceof Number n) {
            if (input == null) {
                return null;
            }
            if (input instanceof List<?> list) {
                int at = n.intValue();
                if (at < 0) {
                    at += list.size();
                }
                if (at < 0 || at >= list.size()) {
                    return null;
                }
                return list.get(at);
            }
            throw new JqError("索引需数组: " + JqValue.typeName(input));
        }
        throw new JqError("非法索引类型: " + JqValue.typeName(key));
    }

    @SuppressWarnings("unchecked")
    private Object buildObject(List<JqParser.Entry> entries, int at, Map<String, Object> acc, Object input, Env env) {
        if (at >= entries.size()) {
            return acc;
        }
        JqParser.Entry entry = entries.get(at);
        List<Object> out = new ArrayList<>();
        for (Object value : eval(entry.value(), input, env)) {
            Map<String, Object> next = new LinkedHashMap<>(acc);
            next.put(entry.key(), value);
            out.addAll(toSingle(buildObject(entries, at + 1, next, input, env)));
        }
        return out.size() == 1 ? out.get(0) : out;
    }

    private static List<Object> toSingle(Object v) {
        return single(v);
    }

    private Object evalReduce(JqParser.Reduce reduce, Object input, Env env) {
        Object acc = eval(reduce.init(), input, env).get(0);
        for (Object item : eval(reduce.source(), input, env)) {
            Map<String, Object> vars = new HashMap<>();
            vars.put(reduce.var(), item);
            Env child = new Env(vars, env);
            acc = eval(reduce.update(), acc, child).get(0);
        }
        return acc;
    }

    private List<Object> evalForeach(JqParser.Foreach foreach, Object input, Env env) {
        List<Object> out = new ArrayList<>();
        Object acc = eval(foreach.init(), input, env).get(0);
        for (Object item : eval(foreach.source(), input, env)) {
            Map<String, Object> vars = new HashMap<>();
            vars.put(foreach.var(), item);
            Env child = new Env(vars, env);
            acc = eval(foreach.update(), acc, child).get(0);
            out.add(foreach.extract() != null ? eval(foreach.extract(), acc, child).get(0) : acc);
        }
        return out;
    }

    private void evalIf(JqParser.If ifNode, int branchAt, Object input, Env env, List<Object> out) {
        if (branchAt >= ifNode.branches().size()) {
            out.addAll(eval(ifNode.elseBody(), input, env));
            return;
        }
        JqParser.Node[] branch = ifNode.branches().get(branchAt);
        for (Object condition : eval(branch[0], input, env)) {
            if (JqValue.isTruthy(condition)) {
                out.addAll(eval(branch[1], input, env));
            } else {
                evalIf(ifNode, branchAt + 1, input, env, out);
            }
        }
    }

    private List<Object> evalBinary(JqParser.Binary binary, Object input, Env env) {
        return switch (binary.op()) {
            case "and" -> {
                List<Object> out = new ArrayList<>();
                for (Object left : eval(binary.left(), input, env)) {
                    if (!JqValue.isTruthy(left)) {
                        out.add(false);
                    } else {
                        for (Object right : eval(binary.right(), input, env)) {
                            out.add(JqValue.isTruthy(right));
                        }
                    }
                }
                yield out;
            }
            case "or" -> {
                List<Object> out = new ArrayList<>();
                for (Object left : eval(binary.left(), input, env)) {
                    if (JqValue.isTruthy(left)) {
                        out.add(true);
                    } else {
                        for (Object right : eval(binary.right(), input, env)) {
                            out.add(JqValue.isTruthy(right));
                        }
                    }
                }
                yield out;
            }
            default -> {
                List<Object> out = new ArrayList<>();
                for (Object left : eval(binary.left(), input, env)) {
                    for (Object right : eval(binary.right(), input, env)) {
                        out.add(applyOp(binary.op(), left, right));
                    }
                }
                yield out;
            }
        };
    }

    private Object applyOp(String op, Object left, Object right) {
        switch (op) {
            case "+" -> {
                if (left instanceof Number a && right instanceof Number b) {
                    return a.doubleValue() + b.doubleValue();
                }
                if (left instanceof String a) {
                    return a + stringify(right);
                }
                if (left instanceof List<?> a && right instanceof List<?> b) {
                    List<Object> merged = new ArrayList<>(a);
                    merged.addAll(b);
                    return merged;
                }
                if (left instanceof Map<?, ?> && right instanceof Map<?, ?>) {
                    Map<String, Object> merged = new LinkedHashMap<>((Map<String, Object>) left);
                    merged.putAll((Map<String, Object>) right);
                    return merged;
                }
                if (left == null) {
                    return right;
                }
                if (right == null) {
                    return left;
                }
                throw new JqError("加法类型不兼容");
            }
            case "-" -> {
                requireNumbers(op, left, right);
                return ((Number) left).doubleValue() - ((Number) right).doubleValue();
            }
            case "*" -> {
                requireNumbers(op, left, right);
                return ((Number) left).doubleValue() * ((Number) right).doubleValue();
            }
            case "/" -> {
                requireNumbers(op, left, right);
                if (((Number) right).doubleValue() == 0) {
                    throw new JqError("除零");
                }
                return ((Number) left).doubleValue() / ((Number) right).doubleValue();
            }
            case "%" -> {
                requireNumbers(op, left, right);
                return (double) (((Number) left).longValue() % ((Number) right).longValue());
            }
            case "==" -> {
                return JqValue.deepEquals(left, right);
            }
            case "!=" -> {
                return !JqValue.deepEquals(left, right);
            }
            case "<" -> {
                return JqValue.compare(left, right) < 0;
            }
            case "<=" -> {
                return JqValue.compare(left, right) <= 0;
            }
            case ">" -> {
                return JqValue.compare(left, right) > 0;
            }
            case ">=" -> {
                return JqValue.compare(left, right) >= 0;
            }
            default -> throw new JqError("未知运算符: " + op);
        }
    }

    private static String stringify(Object v) {
        return v instanceof String s ? s : render(v);
    }

    private void requireNumbers(String op, Object left, Object right) {
        if (!(left instanceof Number) || !(right instanceof Number)) {
            throw new JqError(op + " 需数字");
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> callBuiltin(String name, List<JqParser.Node> args, Object input, Env env) {
        return switch (name) {
            case "not" -> single(!JqValue.isTruthy(input));
            case "empty" -> single();
            case "error" -> throw new JqError(stringify(input));
            case "length" -> single(switch (input) {
                case null -> 0.0;
                case String s -> (double) s.length();
                case List<?> l -> (double) l.size();
                case Map<?, ?> m -> (double) m.size();
                case Number n -> Math.abs(n.doubleValue());
                default -> throw new JqError(typeError("length", input));
            });
            case "type" -> single(JqValue.typeName(input));
            case "keys" -> {
                if (input instanceof Map<?, ?> map) {
                    List<Object> keys = new ArrayList<>(map.keySet());
                    keys.sort(JqValue.comparator());
                    yield single(keys);
                }
                if (input instanceof List<?> list) {
                    List<Object> indexes = new ArrayList<>();
                    for (int i = 0; i < list.size(); i++) {
                        indexes.add((double) i);
                    }
                    yield single(indexes);
                }
                yield single();
            }
            case "values" -> input instanceof Map<?, ?> map ? single(new ArrayList<>(map.values())) : single();
            case "has" -> {
                Object key = eval(args.get(0), input, env).get(0);
                if (input instanceof Map<?, ?> map) {
                    yield single(map.containsKey(key));
                }
                if (input instanceof List<?> list) {
                    yield single(key instanceof Number n && n.intValue() >= 0 && n.intValue() < list.size());
                }
                yield single(false);
            }
            case "map" -> {
                if (!(input instanceof List<?> list)) {
                    throw new JqError("map 需数组");
                }
                List<Object> out = new ArrayList<>();
                for (Object item : list) {
                    out.addAll(eval(args.get(0), item, env));
                }
                yield single(out);
            }
            case "select" -> {
                for (Object condition : eval(args.get(0), input, env)) {
                    if (JqValue.isTruthy(condition)) {
                        yield single(input);
                    }
                }
                yield single();
            }
            case "join" -> {
                Object sep = eval(args.get(0), input, env).get(0);
                if (!(input instanceof List<?> list)) {
                    throw new JqError("join 需数组");
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        sb.append(stringify(sep));
                    }
                    if (list.get(i) != null) {
                        sb.append(stringify(list.get(i)));
                    }
                }
                yield single(sb.toString());
            }
            case "add" -> {
                if (!(input instanceof List<?> list)) {
                    throw new JqError("add 需数组");
                }
                Object acc = null;
                for (Object item : list) {
                    acc = acc == null ? item : applyOp("+", acc, item);
                }
                yield single(acc);
            }
            case "sort" -> {
                if (!(input instanceof List<?> list)) {
                    throw new JqError("sort 需数组");
                }
                List<Object> sorted = new ArrayList<>(list);
                sorted.sort(JqValue.comparator());
                yield single(sorted);
            }
            case "sort_by" -> {
                if (!(input instanceof List<?> list)) {
                    throw new JqError("sort_by 需数组");
                }
                List<Object> sorted = new ArrayList<>(list);
                sorted.sort((a, b) -> {
                    Object ka = eval(args.get(0), a, env).get(0);
                    Object kb = eval(args.get(0), b, env).get(0);
                    return JqValue.compare(ka, kb);
                });
                yield single(sorted);
            }
            case "unique" -> {
                List<Object> sorted = new ArrayList<Object>((List<?>) input);
                sorted.sort(JqValue.comparator());
                List<Object> unique = new ArrayList<>();
                for (Object item : sorted) {
                    if (unique.isEmpty() || !JqValue.deepEquals(unique.get(unique.size() - 1), item)) {
                        unique.add(item);
                    }
                }
                yield single(unique);
            }
            case "reverse" -> {
                List<Object> reversed = new ArrayList<Object>((List<?>) input);
                java.util.Collections.reverse(reversed);
                yield single(reversed);
            }
            case "first" -> input instanceof List<?> list && !list.isEmpty() ? single(list.get(0)) : single();
            case "last" -> input instanceof List<?> list && !list.isEmpty() ? single(list.get(list.size() - 1)) : single();
            case "contains" -> {
                Object needle = eval(args.get(0), input, env).get(0);
                yield single(contains(input, needle));
            }
            case "startswith" -> {
                Object prefix = eval(args.get(0), input, env).get(0);
                if (!(input instanceof String s) || !(prefix instanceof String p)) {
                    throw new JqError("startswith 需字符串");
                }
                yield single(s.startsWith(p));
            }
            case "endswith" -> {
                Object suffix = eval(args.get(0), input, env).get(0);
                if (!(input instanceof String s) || !(suffix instanceof String p)) {
                    throw new JqError("endswith 需字符串");
                }
                yield single(s.endsWith(p));
            }
            case "tostring" -> single(stringify(input));
            case "tonumber" -> input instanceof Number n ? single(n.doubleValue())
                    : input instanceof String s ? single(Double.parseDouble(s))
                    : single();
            default -> throw new JqError("未知函数: " + name);
        };
    }

    private static boolean contains(Object haystack, Object needle) {
        if (haystack instanceof String s && needle instanceof String p) {
            return s.contains(p);
        }
        if (haystack instanceof List<?> list) {
            for (Object item : list) {
                if (contains(item, needle)) {
                    return true;
                }
            }
            return false;
        }
        return JqValue.deepEquals(haystack, needle);
    }

    private static String typeError(String fn, Object input) {
        return fn + ": 不支持类型 " + JqValue.typeName(input);
    }

    /** 可含 null 的流构造（List.of 不允许 null） */
    private static List<Object> single(Object... items) {
        List<Object> out = new ArrayList<>(items.length);
        for (Object item : items) {
            out.add(item);
        }
        return out;
    }

    /** 渲染（Port 输出用） */
    public static String render(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof Number n) {
            return JqValue.numberToString(n.doubleValue());
        }
        if (v instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (v instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(render(list.get(i)));
            }
            return sb.append(']').toString();
        }
        Map<?, ?> map = (Map<?, ?>) v;
        List<String> keys = new ArrayList<String>();
        for (Object key : map.keySet()) {
            keys.add((String) key);
        }
        keys.sort(Comparator.naturalOrder());
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(keys.get(i)).append(':').append(render(map.get(keys.get(i))));
        }
        return sb.append('}').toString();
    }
}

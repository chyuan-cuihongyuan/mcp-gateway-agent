package cn.chyuan.ai.domain.casbinkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 策略模型解析（工单 0952 EC1，casbin 思想）。
 * r/p/e/m 四节解析/缺节拒绝/未知节拒绝；匹配式按 && 切原子（等值比较或内建函数调用）。
 */
public final class CasbinModel {

    /** effect 算子：优先允许 / 优先拒绝 */
    public enum Effect { ALLOW_OVERRIDES, DENY_OVERRIDES }

    /** 匹配原子：fn 为 null 表示 == 等值比较，否则为内建函数名 */
    public static final class Atom {
        public final String fn;
        public final List<String> args;

        Atom(String fn, List<String> args) {
            this.fn = fn;
            this.args = args;
        }
    }

    public final List<String> requestTokens;
    public final List<String> policyTokens;
    public final Effect effect;
    public final List<Atom> matcher;

    private CasbinModel(List<String> requestTokens, List<String> policyTokens, Effect effect, List<Atom> matcher) {
        this.requestTokens = requestTokens;
        this.policyTokens = policyTokens;
        this.effect = effect;
        this.matcher = matcher;
    }

    /** 解析模型文本：四节齐备、未知节拒绝、effect 两种已知形态 */
    public static CasbinModel parse(String text) {
        Map<String, String> sections = new LinkedHashMap<>();
        String current = null;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[")) {
                if (!line.endsWith("]")) {
                    throw new IllegalArgumentException("节名格式非法: " + line);
                }
                current = line.substring(1, line.length() - 1).trim();
                if (sections.putIfAbsent(current, "") != null) {
                    throw new IllegalArgumentException("重复节: " + current);
                }
                continue;
            }
            if (current == null || !line.contains("=")) {
                throw new IllegalArgumentException("节外键值行: " + line);
            }
            int eq = line.indexOf('=');
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (!key.matches("[rpme]")) {
                throw new IllegalArgumentException("未知键: " + key);
            }
            sections.put(current, sections.get(current).isEmpty() ? value : sections.get(current) + "\n" + value);
        }
        String r = require(sections, "request_definition");
        String p = require(sections, "policy_definition");
        String e = require(sections, "policy_effect");
        String m = require(sections, "matchers");
        List<String> extra = new ArrayList<>(sections.keySet());
        extra.removeAll(List.of("request_definition", "policy_definition", "policy_effect", "matchers"));
        if (!extra.isEmpty()) {
            throw new IllegalArgumentException("未知节: " + extra);
        }
        return new CasbinModel(tokens(r), tokens(p), parseEffect(e), parseMatcher(m));
    }

    private static String require(Map<String, String> sections, String name) {
        String value = sections.get(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("缺节: " + name);
        }
        return value;
    }

    private static List<String> tokens(String value) {
        List<String> out = new ArrayList<>();
        for (String token : value.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) {
                throw new IllegalArgumentException("空 token: " + value);
            }
            out.add(t);
        }
        return out;
    }

    private static Effect parseEffect(String value) {
        if (value.startsWith("!some")) {
            return Effect.DENY_OVERRIDES;
        }
        if (value.startsWith("some")) {
            return Effect.ALLOW_OVERRIDES;
        }
        throw new IllegalArgumentException("未知 effect: " + value);
    }

    /** 匹配式解析：按 && 切原子；等值比较或 fn(args) 调用 */
    static List<Atom> parseMatcher(String expression) {
        List<Atom> atoms = new ArrayList<>();
        for (String part : expression.split("&&")) {
            String atom = part.trim();
            if (atom.isEmpty()) {
                throw new IllegalArgumentException("空原子: " + expression);
            }
            int paren = atom.indexOf('(');
            if (atom.contains("==")) {
                int eq = atom.indexOf("==");
                atoms.add(new Atom(null, List.of(atom.substring(0, eq).trim(), atom.substring(eq + 2).trim())));
            } else if (paren > 0 && atom.endsWith(")")) {
                String fn = atom.substring(0, paren).trim();
                requireKnownFunction(fn);
                String argText = atom.substring(paren + 1, atom.length() - 1).trim();
                List<String> args = new ArrayList<>();
                for (String arg : argText.split(",")) {
                    String a = arg.trim();
                    if (a.isEmpty()) {
                        throw new IllegalArgumentException("空参数: " + atom);
                    }
                    args.add(a);
                }
                atoms.add(new Atom(fn, args));
            } else {
                throw new IllegalArgumentException("原子语法非法: " + atom);
            }
        }
        if (atoms.isEmpty()) {
            throw new IllegalArgumentException("匹配式为空");
        }
        return atoms;
    }

    /** 内建函数登记：未知函数解析期拒绝 */
    static void requireKnownFunction(String fn) {
        if (!List.of("keyMatch", "keyMatch2", "regexMatch", "g", "g2").contains(fn)) {
            throw new IllegalArgumentException("未知函数: " + fn);
        }
    }
}

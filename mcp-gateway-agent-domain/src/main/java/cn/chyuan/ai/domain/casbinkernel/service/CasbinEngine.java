package cn.chyuan.ai.domain.casbinkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 策略引擎（工单 0953 EC2 / 0956 EC5 / 0957 EC6，casbin 思想）。
 * policy 行加载落桶重复拒绝/r 对 p 按 m 逐行求值无命中拒绝/effect 优先允许·优先拒绝默认 deny。
 */
public final class CasbinEngine {

    private final CasbinModel model;
    private final RoleManager roles = new RoleManager();
    private final Set<String> seen = new LinkedHashSet<>();
    private final List<List<String>> pRows = new ArrayList<>();
    private final List<Runnable> watchers = new ArrayList<>();

    public CasbinEngine(CasbinModel model) {
        this.model = model;
    }

    /** 加载一行策略：p 落桶、g/g2 进角色图；未知前缀/位数不符/重复拒绝 */
    public void loadPolicy(String line) {
        List<String> tokens = split(line);
        String tag = tokens.get(0);
        List<String> rest = tokens.subList(1, tokens.size());
        switch (tag) {
            case "p" -> {
                if (rest.size() != model.policyTokens.size()) {
                    throw new IllegalArgumentException("p 行位数不符: " + line);
                }
                if (!seen.add(line)) {
                    throw new IllegalArgumentException("重复策略: " + line);
                }
                pRows.add(List.copyOf(rest));
            }
            case "g" -> {
                if (rest.size() != 2) {
                    throw new IllegalArgumentException("g 行位数不符: " + line);
                }
                if (!seen.add(line)) {
                    throw new IllegalArgumentException("重复策略: " + line);
                }
                roles.link(rest.get(0), rest.get(1));
            }
            case "g2" -> {
                if (rest.size() != 2 && rest.size() != 3) {
                    throw new IllegalArgumentException("g2 行位数不符: " + line);
                }
                if (!seen.add(line)) {
                    throw new IllegalArgumentException("重复策略: " + line);
                }
                if (rest.size() == 3) {
                    roles.link(rest.get(0), rest.get(1), rest.get(2));
                } else {
                    roles.link(rest.get(0), rest.get(1), "");
                }
            }
            default -> throw new IllegalArgumentException("未知前缀: " + tag);
        }
    }

    /** enforce：r 对 p 按 m 逐行求值；无命中默认 deny；命中按 effect 算子裁定 */
    public boolean enforce(List<String> request) {
        if (request.size() != model.requestTokens.size()) {
            throw new IllegalArgumentException("请求位数不符: " + request);
        }
        Map<String, String> r = new java.util.HashMap<>();
        for (int i = 0; i < request.size(); i++) {
            r.put(model.requestTokens.get(i), request.get(i));
        }
        for (List<String> row : pRows) {
            if (matchRow(r, row)) {
                return model.effect == CasbinModel.Effect.ALLOW_OVERRIDES;
            }
        }
        return false;
    }

    private boolean matchRow(Map<String, String> r, List<String> row) {
        Map<String, String> p = new java.util.HashMap<>();
        for (int i = 0; i < model.policyTokens.size(); i++) {
            p.put(model.policyTokens.get(i), row.get(i));
        }
        for (CasbinModel.Atom atom : model.matcher) {
            if (!evalAtom(atom, r, p)) {
                return false;
            }
        }
        return true;
    }

    private boolean evalAtom(CasbinModel.Atom atom, Map<String, String> r, Map<String, String> p) {
        List<String> values = atom.args.stream().map(arg -> resolve(arg, r, p)).toList();
        if (atom.fn == null) {
            return values.get(0).equals(values.get(1));
        }
        return switch (atom.fn) {
            case "keyMatch", "keyMatch2", "regexMatch" -> MatchFunctions.apply(atom.fn, values.get(0), values.get(1));
            case "g" -> roles.hasLink(values.get(0), values.get(1));
            case "g2" -> roles.hasLink(values.get(0), values.get(1),
                    values.size() > 2 ? values.get(2) : null);
            default -> throw new IllegalArgumentException("未知函数: " + atom.fn);
        };
    }

    /** 操作数解析：单引号字面量 / r.x / p.x */
    private String resolve(String operand, Map<String, String> r, Map<String, String> p) {
        if (operand.startsWith("'") && operand.endsWith("'") && operand.length() >= 2) {
            return operand.substring(1, operand.length() - 1);
        }
        int dot = operand.indexOf('.');
        if (dot > 0) {
            String scope = operand.substring(0, dot);
            String name = operand.substring(dot + 1);
            if (scope.equals("r") && r.containsKey(name)) {
                return r.get(name);
            }
            if (scope.equals("p") && p.containsKey(name)) {
                return p.get(name);
            }
        }
        throw new IllegalArgumentException("操作数非法: " + operand);
    }

    private List<String> split(String line) {
        List<String> out = new ArrayList<>();
        for (String token : line.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) {
                throw new IllegalArgumentException("空 token: " + line);
            }
            out.add(t);
        }
        return out;
    }

    RoleManager roles() {
        return roles;
    }

    int pRows() {
        return pRows.size();
    }
}

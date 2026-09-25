package cn.chyuan.ai.domain.permkernel.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 许可标志与白名单（工单 0862-0863 CX1·CX2，deno 权限思想）。
 * --allow-read/net/env/run 标志解析（无值 ALL/列表值 LIST/缺失 NONE/未知拒绝）与宿主端口路径白名单匹配。
 */
public final class PermFlags {

    public enum Op { READ, NET, ENV, RUN }

    public enum Mode { NONE, LIST, ALL }

    /** 单项许可：模式与条目 */
    public record Scope(Op op, Mode mode, List<String> entries) {
        public Scope {
            entries = List.copyOf(entries);
        }

        public static Scope none(Op op) {
            return new Scope(op, Mode.NONE, List.of());
        }

        public static Scope all(Op op) {
            return new Scope(op, Mode.ALL, List.of());
        }

        public static Scope list(Op op, List<String> entries) {
            return new Scope(op, Mode.LIST, entries);
        }
    }

    private PermFlags() {
    }

    /** 解析 --allow-* 标志序列；重复 op 合并条目；未知标志拒绝 */
    public static List<Scope> parse(List<String> args) {
        Map<Op, Set<String>> entries = new LinkedHashMap<>();
        Map<Op, Mode> modes = new LinkedHashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--allow-")) {
                throw new IllegalArgumentException("未知标志: " + arg);
            }
            String body = arg.substring("--allow-".length());
            String opName = body;
            String value = null;
            int eq = body.indexOf('=');
            if (eq >= 0) {
                opName = body.substring(0, eq);
                value = body.substring(eq + 1);
            }
            Op op = parseOp(opName);
            Mode mode = value == null ? Mode.ALL : Mode.LIST;
            Mode prev = modes.get(op);
            if (prev == Mode.ALL && mode == Mode.LIST) {
                mode = Mode.ALL;
            }
            modes.put(op, mode);
            if (value != null) {
                entries.computeIfAbsent(op, k -> new LinkedHashSet<>())
                        .addAll(Arrays.asList(value.split(",")));
            }
        }
        List<Scope> out = new ArrayList<>();
        for (Op op : Op.values()) {
            Mode mode = modes.getOrDefault(op, Mode.NONE);
            out.add(new Scope(op, mode, new ArrayList<>(entries.getOrDefault(op, new LinkedHashSet<>()))));
        }
        return out;
    }

    private static Op parseOp(String name) {
        try {
            return Op.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知许可类别: " + name);
        }
    }

    /** 白名单匹配：NET 按宿主（精确或 *.后缀）与端口粒度；其余按资源前缀 */
    public static boolean matches(Op op, String entry, String resource) {
        if (op == Op.NET) {
            String entryHost = entry;
            String entryPort = "";
            int colon = entry.indexOf(':');
            if (colon >= 0) {
                entryHost = entry.substring(0, colon);
                entryPort = entry.substring(colon + 1);
            }
            String resHost = resource;
            String resPort = "";
            int resColon = resource.indexOf(':');
            if (resColon >= 0) {
                resHost = resource.substring(0, resColon);
                resPort = resource.substring(resColon + 1);
            }
            boolean hostOk = entryHost.startsWith("*.")
                    ? resHost.endsWith(entryHost.substring(1)) || resHost.equals(entryHost.substring(2))
                    : resHost.equals(entryHost);
            boolean portOk = entryPort.isEmpty() || entryPort.equals(resPort);
            return hostOk && portOk;
        }
        return resource.equals(entry) || resource.startsWith(entry);
    }
}

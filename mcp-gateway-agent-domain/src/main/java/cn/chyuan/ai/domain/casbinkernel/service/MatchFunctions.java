package cn.chyuan.ai.domain.casbinkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 内建匹配函数（工单 0955 EC4，casbin 思想）。
 * keyMatch 精确或 * 前缀/keyMatch2 :段占位符/regexMatch 正则全匹配。
 */
public final class MatchFunctions {

    private MatchFunctions() {
    }

    /** keyMatch：key2 无 * 时全等；有 * 时按 * 前缀匹配 */
    public static boolean keyMatch(String key1, String key2) {
        int star = key2.indexOf('*');
        if (star == -1) {
            return key1.equals(key2);
        }
        if (key1.length() > star) {
            return key1.substring(0, star).equals(key2.substring(0, star));
        }
        return key1.equals(key2.substring(0, star));
    }

    /** keyMatch2：:段占位符匹配任意单段（含 /* 形式），其余全等 */
    public static boolean keyMatch2(String key1, String key2) {
        String[] parts1 = key1.split("/");
        String[] parts2 = key2.split("/");
        int common = Math.min(parts1.length, parts2.length);
        for (int i = 0; i < common; i++) {
            String p = parts2[i];
            if (p.startsWith(":")) {
                if (parts1[i].isEmpty()) {
                    return false;
                }
                continue;
            }
            if (p.endsWith("*")) {
                return parts1[i].startsWith(p.substring(0, p.length() - 1));
            }
            if (!p.equals(parts1[i])) {
                return false;
            }
        }
        return parts1.length == parts2.length;
    }

    /** regexMatch：key2 作为正则对 key1 全匹配 */
    public static boolean regexMatch(String key1, String key2) {
        return java.util.regex.Pattern.compile(key2).matcher(key1).matches();
    }

    /** 按名分发（未知函数调用方在模型解析期已拒绝，此处兜底） */
    public static boolean apply(String fn, String a, String b) {
        return switch (fn) {
            case "keyMatch" -> keyMatch(a, b);
            case "keyMatch2" -> keyMatch2(a, b);
            case "regexMatch" -> regexMatch(a, b);
            default -> throw new IllegalArgumentException("未知函数: " + fn);
        };
    }

    /** 双参函数名单（g/g2 走角色图不在此列） */
    public static List<String> names() {
        return List.of("keyMatch", "keyMatch2", "regexMatch");
    }
}

package cn.chyuan.ai.domain.session.service.message.handler.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 危险工具 consent 策略（SELFLOOP2 loop-233，J05 一期：标记不阻断）。
 * 模式语法（逗号分隔）：精确名 / 前缀* / *后缀 / *包含*；默认空 = 关闭。
 * 命中工具在 TOOL_AUDIT 审计行标记 consent=required，为二期确认门提供策略基础。
 */
@Component
public class ConsentPolicy {

    private final List<String> patterns;

    public ConsentPolicy(@Value("${mcp.consent.tool-patterns:}") String patternsCsv) {
        this.patterns = patternsCsv == null || patternsCsv.isBlank()
                ? List.of()
                : Arrays.stream(patternsCsv.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
    }

    /** 是否需要 consent 标记（高危工具） */
    public boolean requiresConsent(String toolName) {
        if (toolName == null || patterns.isEmpty()) {
            return false;
        }
        return patterns.stream().anyMatch(p -> matches(p, toolName));
    }

    /** 四型匹配：前缀* / *后缀 / *包含* / 精确名（零依赖实现） */
    static boolean matches(String pattern, String toolName) {
        boolean prefix = pattern.endsWith("*") && !pattern.startsWith("*");
        boolean suffix = pattern.startsWith("*") && !pattern.endsWith("*");
        boolean contains = pattern.startsWith("*") && pattern.endsWith("*") && pattern.length() >= 2;
        if (contains) {
            return toolName.contains(pattern.substring(1, pattern.length() - 1));
        }
        if (prefix) {
            return toolName.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        if (suffix) {
            return toolName.endsWith(pattern.substring(1));
        }
        return pattern.equals(toolName);
    }
}

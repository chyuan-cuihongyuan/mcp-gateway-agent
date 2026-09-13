package cn.chyuan.ai.domain.session.service.message.handler.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 工具返回体积护栏（b-19 / 工单 1134，buzhou spill 思想 mcp 侧移植）。
 * <p>
 * 超限文本截断并附显式标记（对 LLM 诚实说明信息不完整）；
 * {@code mcp.tool-result.max-chars:0} 显式关闭；默认 30000 字符——
 * 护栏就该默认在。
 */
@Slf4j
@Component
public class ToolResultGuard {

    @Value("${mcp.tool-result.max-chars:30000}")
    private int maxChars;

    /** 截断标记（LLM 可读：说明信息不完整及损失量） */
    static final String SPILL_MARKER = "\n…[spilled: 截断 %d 字符（上限 %d）]";

    /**
     * 护栏：null/未超限原样透传；超限文本截断并附标记。
     */
    public Object guard(Object result, String toolName) {
        if (maxChars <= 0 || result == null) {
            return result;
        }
        String text = String.valueOf(result);
        if (text.length() <= maxChars) {
            return result;
        }
        long dropped = text.length() - (long) maxChars;
        log.warn("工具返回超限截断: toolName={}, 原始={} 字符, 上限={}", toolName, text.length(), maxChars);
        return text.substring(0, maxChars) + String.format(SPILL_MARKER, dropped, maxChars);
    }
}

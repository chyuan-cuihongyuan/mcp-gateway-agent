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
 * <p>
 * SELFLOOP7 loop-818（OWASP MCP06 / 工单 3035/3036）：可选 delimiter 包裹
 * （Microsoft 间接注入指南首选防御）——{@code mcp.tool-result.delimit:true}
 * 开启后返回体包裹 tool_data 边界标记，先包裹后截断（边界标记不可被截掉）。
 * 默认关：包裹改变返回体文本形态（可能影响下游 host 的解析习惯），opt-in 制。
 */
@Slf4j
@Component
public class ToolResultGuard {

    @Value("${mcp.tool-result.max-chars:30000}")
    private int maxChars;

    /** delimiter 包裹开关（默认关，见类注释） */
    @Value("${mcp.tool-result.delimit:false}")
    private boolean delimit;

    /** 截断标记（LLM 可读：说明信息不完整及损失量） */
    static final String SPILL_MARKER = "\n…[spilled: 截断 %d 字符（上限 %d）]";

    static final String DELIMIT_OPEN = "<tool_data tool=\"%s\">\n";
    static final String DELIMIT_CLOSE = "\n</tool_data>";

    /**
     * 护栏：null 原样透传；可选包裹（先包裹后截断，边界标记存活）；超限截断附标记。
     */
    public Object guard(Object result, String toolName) {
        if (result == null) {
            return result;
        }
        String text = String.valueOf(result);
        if (delimit) {
            text = String.format(DELIMIT_OPEN, toolName) + text + DELIMIT_CLOSE;
        }
        if (maxChars <= 0 || text.length() <= maxChars) {
            return delimit ? text : result;
        }
        long dropped = text.length() - (long) maxChars;
        log.warn("工具返回超限截断: toolName={}, 原始={} 字符, 上限={}", toolName, text.length(), maxChars);
        return text.substring(0, maxChars) + String.format(SPILL_MARKER, dropped, maxChars);
    }
}

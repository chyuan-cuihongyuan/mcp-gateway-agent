package cn.chyuan.ai.domain.session.service.message.handler.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工具调用审计日志（SELFLOOP2 loop-208）。
 * 单行结构化事件（key=value），专用 logger 名 TOOL_AUDIT 便于采集过滤。
 * 数据最小化：参数只记键名集合，值一律不落（OWASP Logging Cheat Sheet）。
 */
@Component
public class ToolCallAuditLogger {

    public static final String LOGGER_NAME = "TOOL_AUDIT";

    private final Logger auditLog = LoggerFactory.getLogger(LOGGER_NAME);

    private final ToolTagCatalog tagCatalog;

    private final ToolAnnotationsCatalog annotationsCatalog;

    public ToolCallAuditLogger(ToolTagCatalog tagCatalog) {
        this(tagCatalog, new ToolAnnotationsCatalog(""));
    }

    public ToolCallAuditLogger(ToolTagCatalog tagCatalog, ToolAnnotationsCatalog annotationsCatalog) {
        this.tagCatalog = tagCatalog;
        this.annotationsCatalog = annotationsCatalog;
    }

    /**
     * 记录一笔工具调用审计事件。
     *
     * @param gatewayId  网关标识（谁）
     * @param toolName   工具名（调了什么）
     * @param arguments  原始参数对象（仅提取键名/类型，不落值）
     * @param ok         是否成功
     * @param durationMs 耗时
     * @param errCode    失败错误码（成功传 "-"）
     */
    public void audit(String gatewayId, String toolName, Object arguments,
                      boolean ok, long durationMs, String errCode) {
        audit(gatewayId, toolName, arguments, ok, durationMs, errCode, false);
    }

    /** 带 consent 标记的重载（SELFLOOP2 loop-233：高危工具审计可见化） */
    public void audit(String gatewayId, String toolName, Object arguments,
                      boolean ok, long durationMs, String errCode, boolean consentRequired) {
        String tags = tagCatalog.auditValue(nz(toolName));
        String annotations = annotationsCatalog.auditValue(nz(toolName));
        String line = "event=tool_call"
                + " gatewayId=" + nz(gatewayId)
                + " toolName=" + nz(toolName)
                + " argKeys=" + argKeys(arguments)
                + " ok=" + ok
                + " durationMs=" + durationMs
                + " errCode=" + (errCode == null ? "-" : errCode)
                + (consentRequired ? " consent=required" : "")
                + (tags != null ? " tags=" + tags : "")
                + (annotations != null ? " annotations=" + annotations : "")
                + " traceId=" + nz(MDC.get("traceId"));
        auditLog.info(line);
    }

    /** 参数脱敏：Map 取键名集合；其他类型只记类型名；null 记 "-" */
    static String argKeys(Object arguments) {
        if (arguments == null) {
            return "-";
        }
        if (arguments instanceof Map<?, ?> args) {
            return args.keySet().stream().map(String::valueOf).sorted().collect(Collectors.joining(","));
        }
        return "type:" + arguments.getClass().getSimpleName();
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}

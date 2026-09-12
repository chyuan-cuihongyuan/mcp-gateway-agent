package cn.chyuan.ai.domain.session.service.message.handler.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具调用审计日志契约测试（SELFLOOP2 loop-208）：
 * 专用 logger 名 TOOL_AUDIT、字段齐全、参数只记键不记值。
 */
@DisplayName("ToolCallAuditLogger 审计契约")
class ToolCallAuditLoggerTest {

    private ToolCallAuditLogger auditLogger;
    private Logger toolAuditLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        auditLogger = new ToolCallAuditLogger();
        toolAuditLogger = (Logger) LoggerFactory.getLogger(ToolCallAuditLogger.LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        toolAuditLogger.addAppender(appender);
        toolAuditLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        toolAuditLogger.detachAppender(appender);
    }

    private String lastLine() {
        return appender.list.get(appender.list.size() - 1).getFormattedMessage();
    }

    @Test
    @DisplayName("成功事件：字段齐全，argKeys 为排序键名")
    void successEvent_fields() {
        auditLogger.audit("gw-1", "query_order",
                Map.of("orderId", "SO-123", "secret", "should-not-appear"),
                true, 42, null);

        String line = lastLine();
        assertTrue(line.contains("event=tool_call"));
        assertTrue(line.contains("gatewayId=gw-1"));
        assertTrue(line.contains("toolName=query_order"));
        assertTrue(line.contains("argKeys=orderId,secret"));
        assertTrue(line.contains("ok=true"));
        assertTrue(line.contains("durationMs=42"));
        assertTrue(line.contains("errCode=-"));
        assertTrue(line.contains("traceId=-"));
    }

    @Test
    @DisplayName("数据最小化：参数值绝不落入审计行")
    void argumentsValuesNeverLogged() {
        auditLogger.audit("gw-1", "query_order",
                Map.of("orderId", "SO-SECRET-VALUE"), false, 5, "E001");

        assertFalse(lastLine().contains("SO-SECRET-VALUE"));
    }

    @Test
    @DisplayName("失败事件：errCode 透传；null 参数 argKeys=-")
    void failureEvent() {
        auditLogger.audit(null, null, null, false, 7, "E002");

        String line = lastLine();
        assertTrue(line.contains("ok=false"));
        assertTrue(line.contains("errCode=E002"));
        assertTrue(line.contains("gatewayId=-"));
        assertTrue(line.contains("toolName=-"));
        assertTrue(line.contains("argKeys=-"));
    }

    @Test
    @DisplayName("非 Map 参数只记类型名")
    void nonMapArguments() {
        auditLogger.audit("gw-1", "t", "plain-string", true, 1, null);
        assertTrue(lastLine().contains("argKeys=type:String"));
    }

    @Test
    @DisplayName("argKeys 静态方法：null/Map/其他类型三态")
    void argKeysStatic() {
        assertEquals("-", ToolCallAuditLogger.argKeys(null));
        assertEquals("a,b", ToolCallAuditLogger.argKeys(Map.of("b", 1, "a", 2)));
        assertEquals("type:Integer", ToolCallAuditLogger.argKeys(42));
    }
}

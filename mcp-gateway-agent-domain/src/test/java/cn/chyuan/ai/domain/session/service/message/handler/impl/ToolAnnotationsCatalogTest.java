package cn.chyuan.ai.domain.session.service.message.handler.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * b-38 工具注解目录契约：条目 CSV 解析、别名归一、非法条目静默跳过、
 * 未配置工具空注解、审计行 annotations= 值格式（b-12 标签层同构）。
 */
@DisplayName("ToolAnnotationsCatalog 注解契约（b-38）")
class ToolAnnotationsCatalogTest {

    @Test
    @DisplayName("条目解析：tool=key:val 竖线分隔多 hint，大小写归一")
    void parsesEntries() {
        ToolAnnotationsCatalog catalog = new ToolAnnotationsCatalog(
                "agent_order_query=readOnly:true|idempotent:TRUE, agent_invoice_query=openworld:false");

        assertEquals(Map.of("ro", true, "idem", true), catalog.annotationsOf("agent_order_query"));
        assertEquals(Map.of("open", false), catalog.annotationsOf("agent_invoice_query"));
    }

    @Test
    @DisplayName("未配置工具：空注解；null 工具名安全")
    void unknownToolHasNoAnnotations() {
        ToolAnnotationsCatalog catalog = new ToolAnnotationsCatalog("agent_order_query=readonly:true");
        assertTrue(catalog.annotationsOf("agent_unknown").isEmpty());
        assertTrue(catalog.annotationsOf(null).isEmpty());
    }

    @Test
    @DisplayName("非法条目静默跳过：缺等号/未知键/坏布尔/空串")
    void invalidEntriesSkipped() {
        ToolAnnotationsCatalog catalog = new ToolAnnotationsCatalog(
                "noequals, agent_bad= , =nope, agent_a=weird:true, agent_b=readonly:yes, agent_c=readonly:true");

        assertTrue(catalog.annotationsOf("noequals").isEmpty());
        assertTrue(catalog.annotationsOf("agent_bad").isEmpty());
        assertTrue(catalog.annotationsOf("agent_a").isEmpty());
        assertTrue(catalog.annotationsOf("agent_b").isEmpty());
        assertEquals(Map.of("ro", true), catalog.annotationsOf("agent_c"));
    }

    @Test
    @DisplayName("审计值：ro=true|destr=false 竖线拼接；未配置为 null")
    void auditValueFormat() {
        ToolAnnotationsCatalog catalog = new ToolAnnotationsCatalog(
                "agent_order_refund_apply=readonly:false|destructive:true");
        String value = catalog.auditValue("agent_order_refund_apply");

        assertEquals("ro=false|destr=true", value);
        assertNull(catalog.auditValue("agent_unknown"));
        assertNull(catalog.auditValue(null));
    }
}

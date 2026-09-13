package cn.chyuan.ai.domain.session.service.message.handler.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * b-12 工具标签目录契约：条目 CSV 解析、非法条目静默跳过、
 * 未配置工具空标签、审计行 tags= 值格式。
 */
@DisplayName("ToolTagCatalog 标签契约（b-12）")
class ToolTagCatalogTest {

    @Test
    @DisplayName("条目解析：tool=tag1|tag2 逗号分隔多工具")
    void parsesEntries() {
        ToolTagCatalog catalog = new ToolTagCatalog(
                "agent_order_query=order|finance, agent_invoice_query=invoice");

        assertEquals(List.of("order", "finance"), catalog.tagsOf("agent_order_query"));
        assertEquals(List.of("invoice"), catalog.tagsOf("agent_invoice_query"));
    }

    @Test
    @DisplayName("未配置工具：空标签；null 工具名安全")
    void unknownToolHasNoTags() {
        ToolTagCatalog catalog = new ToolTagCatalog("agent_order_query=order");
        assertTrue(catalog.tagsOf("agent_unknown").isEmpty());
        assertTrue(catalog.tagsOf(null).isEmpty());
    }

    @Test
    @DisplayName("非法条目静默跳过：缺等号/空标签/空串")
    void invalidEntriesSkipped() {
        ToolTagCatalog catalog = new ToolTagCatalog(
                "noequals, agent_bad= , =notag, agent_order_query=order");

        assertTrue(catalog.tagsOf("noequals").isEmpty());
        assertTrue(catalog.tagsOf("agent_bad").isEmpty());
        assertEquals(List.of("order"), catalog.tagsOf("agent_order_query"));
    }

    @Test
    @DisplayName("空配置 = 无标签（与 loop-233 consent 空配置同构）")
    void blankConfigMeansNoTags() {
        ToolTagCatalog catalog = new ToolTagCatalog("");
        assertTrue(catalog.tagsOf("agent_order_query").isEmpty());
        assertNull(catalog.auditValue("agent_order_query"));
    }

    @Test
    @DisplayName("审计行值：标签以 | 连接")
    void auditValueJoinsTags() {
        ToolTagCatalog catalog = new ToolTagCatalog("agent_order_query=order|finance");
        assertEquals("order|finance", catalog.auditValue("agent_order_query"));
    }
}

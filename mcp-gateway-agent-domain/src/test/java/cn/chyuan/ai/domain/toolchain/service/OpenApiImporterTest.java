package cn.chyuan.ai.domain.toolchain.service;

import cn.chyuan.ai.domain.toolchain.model.valobj.ImportReportVO;
import cn.chyuan.ai.domain.toolchain.model.valobj.ToolDefinitionVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAPI 导入器单测（工单 0331 AP1）：解析/派生/冲突/非法文档。
 */
class OpenApiImporterTest {

    private final OpenApiImporter importer = new OpenApiImporter();

    private static final String DOC = """
            {
              "openapi": "3.0.0",
              "info": {"title": "样例 API", "version": "1.0"},
              "paths": {
                "/users/{id}": {
                  "get": {
                    "operationId": "getUser",
                    "summary": "查询用户",
                    "parameters": [
                      {"name": "id", "in": "path", "required": true, "schema": {"type": "integer"}},
                      {"name": "verbose", "in": "query", "schema": {"type": "boolean"}}
                    ]
                  }
                },
                "/orders": {
                  "post": {
                    "summary": "创建订单",
                    "parameters": [{"name": "body", "in": "body", "schema": {"type": "string"}}]
                  }
                }
              }
            }""";

    @Test
    void 路径操作参数解析正确() {
        OpenApiImporter.ImportResult result = importer.importDocument(DOC, List.of());
        assertTrue(result.report().isSuccess());
        assertEquals(2, result.definitions().size());
        assertEquals(2, result.report().getImported().size());
        ToolDefinitionVO getUser = result.definitions().stream()
                .filter(d -> d.getName().equals("getUser")).findFirst().orElseThrow();
        assertEquals("get", getUser.getMethod());
        assertEquals("/users/{id}", getUser.getPath());
        assertEquals("查询用户", getUser.getDescription());
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) getUser.getParameterSchema().get("required");
        assertEquals(List.of("id"), required);
        // operationId 缺失 → method+路径派生
        ToolDefinitionVO derived = result.definitions().stream()
                .filter(d -> !d.getName().equals("getUser")).findFirst().orElseThrow();
        assertEquals("post_orders", derived.getName());
        assertEquals("post_orders", result.report().getImported().get(1));
    }

    @Test
    void 同指纹跳过与异指纹冲突() {
        OpenApiImporter.ImportResult first = importer.importDocument(DOC, List.of());
        // 同指纹重复导入 → 跳过
        OpenApiImporter.ImportResult again = importer.importDocument(DOC, first.definitions());
        assertEquals(2, again.report().getSkipped().size());
        assertTrue(again.report().getImported().isEmpty());
        // 修改文档（同 operationId 不同指纹）→ 冲突
        OpenApiImporter.ImportResult changed = importer.importDocument(
                DOC.replace("查询用户", "查询用户 V2"), first.definitions());
        assertEquals(2, changed.report().getConflicts().size());
        assertFalse(changed.report().getSkipped().contains("getUser"));
    }

    @Test
    void 非法文档失败报告() {
        ImportReportVO badJson = importer.importDocument("{不是 JSON", List.of()).report();
        assertFalse(badJson.isSuccess());
        assertTrue(badJson.getError().contains("非法"));
        ImportReportVO noPaths = importer.importDocument("{\"info\": {}}", List.of()).report();
        assertFalse(noPaths.isSuccess());
        assertTrue(noPaths.getError().contains("paths"));
        ImportReportVO nullDoc = importer.importDocument(null, List.of()).report();
        assertFalse(nullDoc.isSuccess());
    }

    @Test
    void 派生与指纹内核() {
        assertEquals("get_users_id", OpenApiImporter.deriveOperationId("get", "/users/{id}"));
        assertEquals("get_root", OpenApiImporter.deriveOperationId("get", "/"));
        assertEquals(64, OpenApiImporter.sha256("x").length());
        assertEquals(OpenApiImporter.sha256("abc"), OpenApiImporter.sha256("abc"), "指纹确定性");
    }
}

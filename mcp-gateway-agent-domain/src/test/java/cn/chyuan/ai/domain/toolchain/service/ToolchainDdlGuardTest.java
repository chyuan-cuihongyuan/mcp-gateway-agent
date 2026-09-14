package cn.chyuan.ai.domain.toolchain.service;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具链三表 DDL 双方言守卫（工单 0337/0335 AP5-AP7，V0019-V0021）：
 * mcp_tool_registry / mcp_tool_chain / mcp_tool_call_log 在 PG changelog 与
 * MySQL v7 增量文件中均有建表定义。
 */
class ToolchainDdlGuardTest {

    @Test
    void postgresqlChangelogDefinesToolchainTables() throws IOException {
        assertTrue(pg("V0019__tool_registry.sql").contains("CREATE TABLE IF NOT EXISTS mcp_tool_registry"), "PG 应含工具注册表");
        assertTrue(pg("V0019__tool_registry.sql").contains("CONSTRAINT uk_tool_registry_name UNIQUE (tool_name)"), "PG 应含工具名唯一键");
        assertTrue(pg("V0020__tool_chain.sql").contains("CREATE TABLE IF NOT EXISTS mcp_tool_chain"), "PG 应含工具链表");
        assertTrue(pg("V0021__tool_call_log.sql").contains("CREATE TABLE IF NOT EXISTS mcp_tool_call_log"), "PG 应含调用审计表");
        assertTrue(pg("V0021__tool_call_log.sql").contains("idx_tool_call_tenant_tool"), "PG 应含租户×工具索引");
    }

    @Test
    void mysqlUpgradeV7DefinesToolchainTables() throws IOException {
        String ddl = read("../mcp-gateway-agent-app/src/main/resources/sql/mysql-upgrade-v7-capability-seven.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS mcp_tool_registry"), "MySQL 应含工具注册表");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS mcp_tool_chain"), "MySQL 应含工具链表");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS mcp_tool_call_log"), "MySQL 应含调用审计表");
        assertTrue(ddl.contains("uk_tool_registry_name"), "MySQL 应含工具名唯一键");
        assertTrue(ddl.contains("工单 0337 AP7"), "MySQL DDL 应带工单口径注释");
    }

    private String pg(String fileName) throws IOException {
        return read("../mcp-gateway-agent-app/src/main/resources/db/changelog/postgresql/" + fileName);
    }

    private String read(String path) throws IOException {
        File file = new File(path);
        assertTrue(file.isFile(), "schema 文件应存在: " + path);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}

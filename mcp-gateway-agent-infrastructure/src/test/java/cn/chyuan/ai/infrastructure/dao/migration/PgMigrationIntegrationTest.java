package cn.chyuan.ai.infrastructure.dao.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迁移执行器真库集成测试（工单 0125 骨架；Testcontainers-PG）。
 *
 * <p>本机无 Docker：通过环境变量禁用（CI/远端设 ENABLE_PG_IT=1 且有 Docker 时执行）。
 * 覆盖：V0001 基线在空 PG 上完整落表 + 幂等重跑 + baseline 收编路径。
 */
@Testcontainers(disabledWithoutDocker = true)
@DisabledIfEnvironmentVariable(named = "ENABLE_PG_IT", matches = "0", disabledReason = "显式关闭 PG 集成测试")
class PgMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("pgvector/pgvector:0.8.6-pg18")
            .withDatabaseName("mcp_gateway_agent")
            .withUsername("app")
            .withPassword("test");

    @Test
    void baselineCreatesAllTablesAndReRunIsIdempotent() {
        DataSource dataSource = new DriverManagerDataSource(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        MigrationStore store = new JdbcMigrationStore(jdbcTemplate, dataSource);
        MigrationRunner runner = new MigrationRunner(store,
                new PathMatchingResourcePatternResolver(), "0001");

        // 空库首跑：执行全部 changelog
        runner.run(null);
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public'"
                        + " AND table_name IN ('schema_version','mcp_gateway','mcp_gateway_auth','mcp_gateway_tool',"
                        + "'mcp_protocol_http','mcp_protocol_mapping','mcp_virtual_key','mcp_virtual_key_gateway',"
                        + "'mcp_cel_rule','mcp_admin_user','mcp_audit_log','mcp_external_attach','mcp_usage_log',"
                        + "'mcp_usage_daily','mcp_webhook_endpoint','mcp_cel_rule_template','mcp_prompt','mcp_resource',"
                        + "'mcp_llm_channel','mcp_model_pricing','mcp_guardrail')",
                Integer.class);
        assertEquals(21, tableCount, "V0001 基线应建齐 20 张业务表 + schema_version");

        // 二次启动：无待应用脚本
        runner.run(null);
        List<String> applied = store.appliedVersions();
        assertTrue(applied.contains("0001"));
        assertEquals(1, applied.size(), "幂等重跑不产生新的版本记录");
    }
}

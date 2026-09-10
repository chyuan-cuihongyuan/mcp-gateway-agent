package cn.chyuan.ai.infrastructure.dao.migration;

import cn.chyuan.ai.domain.governance.service.MigrationPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * 迁移存储 JDBC 实现（工单 0125）。
 *
 * <p>schema_version 建表/查询/记录 + ScriptUtils 脚本执行；方言经
 * DatabaseMetaData 产品名识别。仅 PostgreSQL 方言被 Runner 放行到这里
 * 之后的 changelog 流程（MySQL 走 legacy 幂等种子）。
 *
 * @author chyuan
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class JdbcMigrationStore implements MigrationStore {

    private static final String CREATE_SCHEMA_VERSION = """
            CREATE TABLE IF NOT EXISTS schema_version (
              version     VARCHAR(32)  NOT NULL PRIMARY KEY,
              description VARCHAR(256) NOT NULL,
              checksum    VARCHAR(64)  NOT NULL,
              applied_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
            )""";

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    @Override
    public String dialect() {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            if (product == null) {
                return "";
            }
            if (product.contains("PostgreSQL")) {
                return "postgresql";
            }
            if (product.contains("MySQL")) {
                return "mysql";
            }
            return product;
        } catch (SQLException e) {
            throw new IllegalStateException("读取数据源方言失败", e);
        }
    }

    @Override
    public void ensureVersionTable() {
        jdbcTemplate.execute(CREATE_SCHEMA_VERSION);
    }

    @Override
    public boolean coreTableExists() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables"
                        + " WHERE table_schema = current_schema() AND table_name = 'mcp_gateway'",
                Integer.class);
        return count != null && count > 0;
    }

    @Override
    public List<String> appliedVersions() {
        return jdbcTemplate.queryForList("SELECT version FROM schema_version", String.class);
    }

    @Override
    public void recordApplied(MigrationPlan.Step step) {
        jdbcTemplate.update(
                "INSERT INTO schema_version (version, description, checksum) VALUES (?, ?, ?)",
                step.version(), step.description(), step.checksum());
    }

    @Override
    public void executeScript(String sql) {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                    new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
        } catch (SQLException | org.springframework.jdbc.datasource.init.ScriptException e) {
            throw new IllegalStateException("DDL 脚本执行失败", e);
        }
    }
}

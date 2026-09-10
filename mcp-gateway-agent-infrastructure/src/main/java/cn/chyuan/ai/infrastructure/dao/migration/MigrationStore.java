package cn.chyuan.ai.infrastructure.dao.migration;

import cn.chyuan.ai.domain.governance.service.MigrationPlan;

import java.util.List;

/**
 * 迁移存储端口（工单 0125：MigrationPlan 执行器接线）。
 *
 * <p>承载 schema_version 表读写、方言探测与脚本执行的 IO；单测以 mock 替身，
 * 真库行为由 {@code PgMigrationIntegrationTest}（Testcontainers，本机无 Docker 跳过）覆盖。
 *
 * @author chyuan
 */
public interface MigrationStore {

    /** 数据源方言：postgresql / mysql / 原始产品名。 */
    String dialect();

    /** 建 schema_version 版本记录表（幂等）。 */
    void ensureVersionTable();

    /** 核心表（mcp_gateway）是否已存在——存量库 baseline 判定。 */
    boolean coreTableExists();

    /** 已应用版本清单。 */
    List<String> appliedVersions();

    /** 记录一条已应用版本。 */
    void recordApplied(MigrationPlan.Step step);

    /** 执行一段 DDL 脚本（语句以分号分隔，支持 -- 注释）。 */
    void executeScript(String sql);
}

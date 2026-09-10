package cn.chyuan.ai.infrastructure.dao.migration;

import cn.chyuan.ai.domain.governance.service.MigrationPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 迁移执行器单测（工单 0125）：方言放行、字典序执行、baseline 收编、
 * 已应用跳过、MySQL 直通、失败 fail-fast。store/resolver 全 mock，不碰真库。
 */
class MigrationRunnerTest {

    private MigrationStore store;
    private ResourcePatternResolver resolver;
    private MigrationRunner runner;

    @BeforeEach
    void setUp() {
        store = mock(MigrationStore.class);
        resolver = mock(ResourcePatternResolver.class);
        runner = new MigrationRunner(store, resolver, "0001");
    }

    private Resource script(String filename, String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private void givenScripts(Resource... resources) throws Exception {
        when(resolver.getResources(MigrationRunner.CHANGELOG_PATTERN)).thenReturn(resources);
    }

    @Test
    void postgresNewDatabaseExecutesAllInVersionOrder() throws Exception {
        when(store.dialect()).thenReturn("postgresql");
        when(store.appliedVersions()).thenReturn(List.of());
        when(store.coreTableExists()).thenReturn(false);
        givenScripts(
                script("V0002__add_x.sql", "CREATE TABLE x (id INT);"),
                script("V0001__pg_baseline.sql", "CREATE TABLE base (id INT);"));

        runner.run(null);

        InOrder inOrder = inOrder(store);
        inOrder.verify(store).ensureVersionTable();
        inOrder.verify(store).executeScript("CREATE TABLE base (id INT);");
        inOrder.verify(store).executeScript("CREATE TABLE x (id INT);");
        verify(store, times(2)).recordApplied(any(MigrationPlan.Step.class));
        // 新库不做 baseline 收编
        verify(store, never()).recordApplied(org.mockito.ArgumentMatchers.argThat(argThatBaseline()));
    }

    @Test
    void existingPgDatabaseBaselinesAndOnlyRunsLaterScripts() throws Exception {
        when(store.dialect()).thenReturn("postgresql");
        when(store.appliedVersions()).thenReturn(List.of());
        when(store.coreTableExists()).thenReturn(true);
        givenScripts(
                script("V0001__pg_baseline.sql", "CREATE TABLE base (id INT);"),
                script("V0002__add_x.sql", "CREATE TABLE x (id INT);"));

        runner.run(null);

        // 存量库：baseline 收编记录 V0001，基线脚本不执行，仅执行其后脚本
        verify(store).recordApplied(new MigrationPlan.Step("0001", "baseline 收编（存量 PG 库）", ""));
        verify(store, times(1)).executeScript(anyString());
        verify(store).executeScript("CREATE TABLE x (id INT);");
    }

    @Test
    void alreadyAppliedVersionIsSkipped() throws Exception {
        when(store.dialect()).thenReturn("postgresql");
        when(store.appliedVersions()).thenReturn(List.of("0001"));
        when(store.coreTableExists()).thenReturn(true);
        givenScripts(
                script("V0001__pg_baseline.sql", "CREATE TABLE base (id INT);"),
                script("V0002__add_x.sql", "CREATE TABLE x (id INT);"));

        runner.run(null);

        verify(store, never()).executeScript("CREATE TABLE base (id INT);");
        verify(store).executeScript("CREATE TABLE x (id INT);");
    }

    @Test
    void mysqlDialectSkipsChangelogEntirely() throws Exception {
        when(store.dialect()).thenReturn("mysql");

        runner.run(null);

        verify(store, never()).ensureVersionTable();
        verify(store, never()).executeScript(anyString());
        verify(store, never()).recordApplied(any(MigrationPlan.Step.class));
    }

    @Test
    void executionFailureFailsFastWithVersionInMessage() throws Exception {
        when(store.dialect()).thenReturn("postgresql");
        when(store.appliedVersions()).thenReturn(List.of());
        when(store.coreTableExists()).thenReturn(false);
        givenScripts(
                script("V0001__pg_baseline.sql", "CREATE TABLE base (id INT);"),
                script("V0002__bad.sql", "THIS IS NOT SQL"));
        org.mockito.Mockito.doThrow(new IllegalStateException("语法错误"))
                .when(store).executeScript("THIS IS NOT SQL");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> runner.run(null));
        assertTrue(ex.getMessage().contains("V0002"), "异常信息应包含失败版本号，实际: " + ex.getMessage());
        // V0001 先于失败脚本，应已执行
        verify(store).executeScript("CREATE TABLE base (id INT);");
    }

    private static org.mockito.ArgumentMatcher<MigrationPlan.Step> argThatBaseline() {
        return step -> "baseline 收编（存量 PG 库）".equals(step.description());
    }
}

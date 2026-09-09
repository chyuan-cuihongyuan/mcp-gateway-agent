package cn.chyuan.ai.domain.governance.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * DDL 迁移计划内核测试（工单 0114：baseline 收编/字典序/已应用跳过）
 */
@DisplayName("DDL 迁移计划内核测试")
class MigrationPlanTest {

    private static final List<MigrationPlan.Step> SCRIPTS = List.of(
            new MigrationPlan.Step("0001", "baseline", "abc"),
            new MigrationPlan.Step("0002", "pricing", "def"),
            new MigrationPlan.Step("0003", "cost", "ghi"));

    @Test
    @DisplayName("新库（核心表不存在、无版本记录）：全部脚本按字典序待执行")
    void freshDatabase() {
        var plan = MigrationPlan.compute(List.of(), false, "0001", SCRIPTS);
        Assertions.assertFalse(plan.baselineApplied());
        Assertions.assertEquals(3, plan.pending().size());
        Assertions.assertEquals("0001", plan.pending().get(0).version());
    }

    @Test
    @DisplayName("存量库（核心表存在、无版本记录）：baseline 收编，baseline 之前的脚本跳过")
    void baselineAbsorb() {
        var plan = MigrationPlan.compute(List.of(), true, "0002", SCRIPTS);
        Assertions.assertTrue(plan.baselineApplied());
        Assertions.assertEquals(1, plan.pending().size());
        Assertions.assertEquals("0003", plan.pending().get(0).version());
    }

    @Test
    @DisplayName("已应用版本跳过；版本比较按精确匹配")
    void appliedSkipped() {
        var plan = MigrationPlan.compute(List.of("0001", "0002"), false, "0002", SCRIPTS);
        Assertions.assertFalse(plan.baselineApplied());
        Assertions.assertEquals(1, plan.pending().size());
        Assertions.assertEquals("0003", plan.pending().get(0).version());
    }

    @Test
    @DisplayName("文件名版本提取：V{version}__{description}.sql")
    void versionExtraction() {
        Assertions.assertEquals("0016", MigrationPlan.versionOf("V0016__create_gateway.sql"));
        Assertions.assertEquals("", MigrationPlan.versionOf("readme.md"));
    }
}

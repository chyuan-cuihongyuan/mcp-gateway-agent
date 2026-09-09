package cn.chyuan.ai.domain.governance.service;

import java.util.ArrayList;
import java.util.List;

/**
 * DDL 版本化迁移内核（工单 0114，Flyway 模式自制轻量实现）
 *
 * <p>纯函数编排：给定已应用版本与脚本清单，计算待执行计划（字典序；
 * baseline 版本之上的跳过）。IO（读 schema_version/执行脚本/写记录）由
 * infrastructure 的 MigrationRunner 承载——便于无数据库单测。
 *
 * @author chyuan
 */
public final class MigrationPlan {

    private MigrationPlan() {
    }

    /** 待执行脚本 */
    public record Step(String version, String description, String checksum) {
    }

    /** 计划结果 */
    public record Plan(boolean baselineApplied, List<Step> pending) {
    }

    /**
     * 计算迁移计划。
     *
     * @param appliedVersions 已应用版本集合（空集 = 新库或存量库）
     * @param coreTableExists 核心表是否已存在（存量库判定：核心表在且无版本记录 → baseline 收编）
     * @param baselineVersion baseline 版本（存量库整体收编到该版本，不逐个执行历史脚本）
     * @param scripts         脚本清单（version 提取自文件名 V{version}__{description}.sql 字典序）
     */
    public static Plan compute(List<String> appliedVersions, boolean coreTableExists,
            String baselineVersion, List<Step> scripts) {
        List<String> applied = appliedVersions == null ? List.of() : appliedVersions;
        boolean baseline = applied.isEmpty() && coreTableExists;
        List<String> effectiveApplied = new ArrayList<>(applied);
        if (baseline && baselineVersion != null && !baselineVersion.isBlank()) {
            effectiveApplied.add(baselineVersion);
        }
        List<Step> pending = new ArrayList<>();
        for (Step step : scripts) {
            if (effectiveApplied.contains(step.version())) {
                continue;
            }
            // baseline 收编：baseline 之前（含等于）的脚本已包含在存量结构中
            if (baseline && baselineVersion != null && step.version().compareTo(baselineVersion) <= 0) {
                continue;
            }
            pending.add(step);
        }
        return new Plan(baseline, pending);
    }

    /** 从脚本文件名提取版本：V0016__create_gateway.sql → 0016 */
    public static String versionOf(String filename) {
        if (filename == null) {
            return "";
        }
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("^V(\\d+)__").matcher(filename);
        return matcher.find() ? matcher.group(1) : "";
    }
}

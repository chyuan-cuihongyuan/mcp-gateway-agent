package cn.chyuan.ai.infrastructure.dao.migration;

import cn.chyuan.ai.domain.governance.service.MigrationPlan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DDL 迁移执行器（工单 0125：把 0114 的 MigrationPlan 纯函数内核接线为启动时迁移）。
 *
 * <p>流程：方言探测（仅 postgresql 走 changelog；MySQL 沿用 legacy 幂等种子）→
 * 建 schema_version → 加载 classpath*:db/changelog/postgresql/V*.sql（字典序）→
 * MigrationPlan.compute（baseline 收编/已应用跳过）→ 逐脚本执行并记录，失败抛
 * 异常中断启动（fail-fast）。执行先于其它 ApplicationRunner（@Order 最高优先级），
 * 保证表结构在治理面种子引导之前就绪。
 *
 * <p>脚本命名：V{版本}__{描述}.sql，版本零填充字典序比较（如 V0001、V0002）。
 *
 * @author chyuan
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "governance.migration", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class MigrationRunner implements ApplicationRunner {

    static final String CHANGELOG_PATTERN = "classpath*:db/changelog/postgresql/V*.sql";

    private final MigrationStore store;
    private final ResourcePatternResolver resolver;
    private final String baselineVersion;

    public MigrationRunner(MigrationStore store, ResourcePatternResolver resolver,
            @Value("${governance.migration.baseline-version:0001}") String baselineVersion) {
        this.store = store;
        this.resolver = resolver;
        this.baselineVersion = baselineVersion;
    }

    @Override
    public void run(ApplicationArguments args) {
        String dialect = store.dialect();
        if (!"postgresql".equals(dialect)) {
            log.info("[migration] 方言 {} 非 PostgreSQL，跳过 changelog（MySQL 走 legacy 幂等种子）", dialect);
            return;
        }
        store.ensureVersionTable();
        Map<String, String> contentByVersion = new HashMap<>();
        List<MigrationPlan.Step> steps = loadSteps(contentByVersion);
        MigrationPlan.Plan plan = MigrationPlan.compute(
                store.appliedVersions(), store.coreTableExists(), baselineVersion, steps);
        if (plan.baselineApplied()) {
            store.recordApplied(new MigrationPlan.Step(baselineVersion, "baseline 收编（存量 PG 库）", ""));
            log.info("[migration] 存量 PG 库 baseline 收编至 V{}", baselineVersion);
        }
        for (MigrationPlan.Step step : plan.pending()) {
            String content = contentByVersion.get(step.version());
            try {
                store.executeScript(content);
                store.recordApplied(step);
                log.info("[migration] 应用 V{}__{}（checksum={}）", step.version(), step.description(),
                        step.checksum().substring(0, 8));
            } catch (RuntimeException e) {
                throw new IllegalStateException("迁移脚本执行失败：V" + step.version(), e);
            }
        }
        log.info("[migration] 完成：待应用 {} 个脚本（方言=postgresql）", plan.pending().size());
    }

    /** 加载 changelog 脚本为 Step（字典序），脚本内容写入 contentByVersion 供执行。 */
    private List<MigrationPlan.Step> loadSteps(Map<String, String> contentByVersion) {
        List<MigrationPlan.Step> steps = new ArrayList<>();
        try {
            Resource[] resources = resolver.getResources(CHANGELOG_PATTERN);
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                String version = MigrationPlan.versionOf(filename);
                if (version.isEmpty()) {
                    log.warn("[migration] 跳过不匹配 V{{n}}__ 命名的脚本：{}", filename);
                    continue;
                }
                String content;
                try (InputStream in = resource.getInputStream()) {
                    content = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
                }
                contentByVersion.put(version, content);
                steps.add(new MigrationPlan.Step(version, descriptionOf(filename), sha256(content)));
            }
        } catch (IOException e) {
            throw new IllegalStateException("加载 changelog 脚本失败: " + CHANGELOG_PATTERN, e);
        }
        steps.sort(Comparator.comparing(MigrationPlan.Step::version));
        return steps;
    }

    /** V0001__pg_baseline.sql → pg_baseline */
    static String descriptionOf(String filename) {
        int start = filename.indexOf("__");
        int end = filename.lastIndexOf('.');
        return start >= 0 ? filename.substring(start + 2, end) : filename;
    }

    static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}

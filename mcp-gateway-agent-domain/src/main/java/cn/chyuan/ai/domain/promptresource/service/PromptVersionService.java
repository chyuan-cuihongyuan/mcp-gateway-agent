package cn.chyuan.ai.domain.promptresource.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示版本服务（工单 0196 AA1，借鉴 Langfuse prompt management）—
 * 同名提示多版本管理：创建草稿（版本号递增）→ 发布（同名单一 PUBLISHED 不变式，
 * 前任自动转 ROLLBACK）→ 回滚（目标版本重发布，当前版本转 ROLLBACK）。
 * 存储经 {@link VersionStore} 端口（infrastructure 落 prompt_version 表）。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class PromptVersionService {

    /** 同名单一已发布版本不变式违反（存储被旁路写坏时的防御性校验） */
    public static final String ERR_MULTIPLE_PUBLISHED = "同一提示存在多个已发布版本";

    /** 状态：草稿（不可被标签解析引用） */
    public static final String STATUS_DRAFT = "DRAFT";
    /** 状态：已发布（同名唯一） */
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    /** 状态：已被回滚/发布替代 */
    public static final String STATUS_ROLLBACK = "ROLLBACK";

    private static final Set<String> LEGAL_STATUS = Set.of(STATUS_DRAFT, STATUS_PUBLISHED, STATUS_ROLLBACK);

    /** 版本持久化端口（infrastructure 经 MyBatis 落 mcp_prompt_version 表） */
    public interface VersionStore {

        /** 同名提示当前最大版本号（无记录返回 0） */
        int maxVersionOf(String promptName);

        void insert(PromptVersion version);

        void update(PromptVersion version);

        PromptVersion find(String promptName, int version);

        List<PromptVersion> listByName(String promptName);

        List<PromptVersion> listAll();

        /** 按标签解析版本（工单 0197 AA2；无则返回 null） */
        PromptVersion findByLabel(String promptName, String label);

        /** 清空同名提示全部标签（打标前调用，保证同名单一标签持有） */
        void clearLabels(String promptName);
    }

    /** 标签：生产（同名单一持有，工单 0197 AA2） */
    public static final String LABEL_PRODUCTION = "production";
    /** 标签：预发 */
    public static final String LABEL_STAGING = "staging";
    /** 标签：最新 */
    public static final String LABEL_LATEST = "latest";

    private static final Set<String> LEGAL_LABELS = Set.of(LABEL_PRODUCTION, LABEL_STAGING, LABEL_LATEST);

    /** 提示版本值对象 */
    public record PromptVersion(Long id, String promptName, int version, String template,
            String status, String note, String operator, String label) {

        public PromptVersion {
            if (promptName == null || promptName.isBlank()) {
                throw new IllegalArgumentException("promptName 不能为空");
            }
            if (template == null || template.isBlank()) {
                throw new IllegalArgumentException("template 不能为空");
            }
            if (status != null && !LEGAL_STATUS.contains(status)) {
                throw new IllegalArgumentException("非法状态: " + status);
            }
            if (label != null && !LEGAL_LABELS.contains(label)) {
                throw new IllegalArgumentException("非法标签: " + label);
            }
        }

        public boolean published() {
            return STATUS_PUBLISHED.equals(status);
        }
    }

    private final VersionStore store;

    /** 防御：并发发布时按 promptName 串行（同名单一 PUBLISHED 不变式） */
    private final Map<String, Object> publishLocks = new ConcurrentHashMap<>();

    public PromptVersionService(VersionStore store) {
        this.store = store;
    }

    /** 创建草稿：版本号 = 同名当前最大版本 + 1 */
    public PromptVersion createDraft(String promptName, String template, String note, String operator) {
        int next = store.maxVersionOf(promptName) + 1;
        PromptVersion draft = new PromptVersion(null, promptName, next, template, STATUS_DRAFT, note, operator, null);
        store.insert(draft);
        log.info("提示版本草稿创建: name={} version={} operator={}", promptName, next, operator);
        return draft;
    }

    /** 发布：同名单一 PUBLISHED 不变式——前任 PUBLISHED 转 ROLLBACK 后本版本置 PUBLISHED */
    public PromptVersion publish(String promptName, int version, String operator) {
        synchronized (publishLocks.computeIfAbsent(promptName, k -> new Object())) {
            PromptVersion target = require(promptName, version);
            if (target.published()) {
                return target;
            }
            store.listByName(promptName).stream().filter(PromptVersion::published).forEach(prev -> {
                store.update(new PromptVersion(prev.id(), prev.promptName(), prev.version(),
                        prev.template(), STATUS_ROLLBACK, prev.note(), prev.operator(), prev.label()));
            });
            PromptVersion published = new PromptVersion(target.id(), target.promptName(),
                    target.version(), target.template(), STATUS_PUBLISHED, target.note(), operator, target.label());
            store.update(published);
            log.info("提示版本发布: name={} version={} operator={}", promptName, version, operator);
            return published;
        }
    }

    /**
     * 打标签（工单 0197 AA2）：同名单一标签持有——先清同名全部标签再打在目标版本上；
     * 生产惯例建议标签只打 PUBLISHED 版本（不强制，便于 staging 预览草稿）。
     */
    public PromptVersion attachLabel(String promptName, int version, String label) {
        PromptVersion target = require(promptName, version);
        PromptVersion labeled = new PromptVersion(target.id(), target.promptName(), target.version(),
                target.template(), target.status(), target.note(), target.operator(), label);
        store.clearLabels(promptName);
        store.update(labeled);
        log.info("提示版本打标: name={} version={} label={}", promptName, version, label);
        return labeled;
    }

    /**
     * 标签解析（工单 0197 AA2）：label → 具体版本；未打标回退已发布版本；
     * 再无则回退最高版本（均无返回 null）。调用方无感切换的稳定入口。
     */
    public PromptVersion resolveByLabel(String promptName, String label) {
        PromptVersion byLabel = store.findByLabel(promptName, label);
        if (byLabel != null) {
            return byLabel;
        }
        return resolvePublished(promptName);
    }

    /** 回滚到历史版本：目标版本重发布，原发布版本按不变式转 ROLLBACK */
    public PromptVersion rollback(String promptName, int toVersion, String operator) {
        PromptVersion target = require(promptName, toVersion);
        if (STATUS_DRAFT.equals(target.status())) {
            throw new IllegalArgumentException("不能回滚到草稿版本: " + toVersion);
        }
        return publish(promptName, toVersion, operator);
    }

    /** 已发布版本（无则回退最高版本，均无返回 null） */
    public PromptVersion resolvePublished(String promptName) {
        return store.listByName(promptName).stream()
                .filter(PromptVersion::published)
                .findFirst()
                .orElseGet(() -> store.listByName(promptName).stream()
                        .max(Comparator.comparingInt(PromptVersion::version))
                        .orElse(null));
    }

    public PromptVersion get(String promptName, int version) {
        return require(promptName, version);
    }

    public List<PromptVersion> list(String promptName) {
        return store.listByName(promptName);
    }

    public List<PromptVersion> listAll() {
        return store.listAll();
    }

    private PromptVersion require(String promptName, int version) {
        PromptVersion target = store.find(promptName, version);
        if (target == null) {
            throw new IllegalArgumentException("提示版本不存在: " + promptName + " v" + version);
        }
        return target;
    }
}

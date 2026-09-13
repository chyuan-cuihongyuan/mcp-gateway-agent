package cn.chyuan.ai.domain.configcenter.service;

import cn.chyuan.ai.domain.configcenter.service.ConfigBundleCodec.BundleEntry;
import cn.chyuan.ai.domain.configcenter.service.ConfigBundleCodec.ConfigBundle;
import cn.chyuan.ai.domain.configcenter.service.ConfigBundleCodec.ImportReport;
import cn.chyuan.ai.domain.configcenter.service.ConfigSnapshotService.ConfigSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置中心门面（工单 0251-0259 AG 簇编排）：发布链路 = schema 门（AG3）→ 信封加密（AG4）→
 * 快照版本机（AG1）；读取链路 = 当前快照 → 敏感项透明解密。加密开关
 * {@code config.center.encryption.enabled} 默认 false（敏感项明文直存，零行为变化）。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class ConfigCenterFacade {

    private final ConfigSnapshotService snapshotService;
    private final ConfigSchemaGate schemaGate;
    private final EnvelopeCipher cipher;

    @Value("${config.center.encryption.enabled:false}")
    private boolean encryptionEnabled;

    public ConfigCenterFacade(ConfigSnapshotService snapshotService, ConfigSchemaGate schemaGate,
            EnvelopeCipher cipher) {
        this.snapshotService = snapshotService;
        this.schemaGate = schemaGate;
        this.cipher = cipher;
    }

    /** 发布：schema 门 →（敏感且开启加密）加密 → 快照 */
    public ConfigSnapshot publish(String namespace, String configKey, String content,
            boolean sensitive, String publisher, String note) {
        List<String> errors = schemaGate.check(namespace, content);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("schema 校验失败(-" + Math.abs(ConfigSchemaGate.ERR_SCHEMA_INVALID)
                    + "): " + String.join("; ", errors));
        }
        String stored = sensitive && encryptionEnabled ? cipher.encrypt(content) : content;
        return snapshotService.publish(namespace, configKey, stored,
                ConfigDigestCalculator.md5(stored), sensitive, publisher, note);
    }

    /** 读取当前生效配置（敏感项透明解密；解密失败上抛明确错误） */
    public String resolve(String namespace, String configKey) {
        ConfigSnapshot snapshot = snapshotService.current(namespace, configKey);
        if (snapshot == null) {
            return null;
        }
        if (snapshot.sensitive() && encryptionEnabled) {
            return cipher.decrypt(snapshot.content());
        }
        return snapshot.content();
    }

    /** 回滚（沿用原密文/原敏感标记；读取链路统一解密） */
    public ConfigSnapshot rollback(String namespace, String configKey, int toVersion,
            String publisher, String note) {
        return snapshotService.rollback(namespace, configKey, toVersion, publisher, note);
    }

    /** bundle 导出：各键当前快照；敏感项保持密文出站 */
    public ConfigBundle exportBundle(String namespace) {
        List<BundleEntry> entries = new ArrayList<>();
        Map<String, ConfigSnapshot> currentByKey = new LinkedHashMap<>();
        snapshotService.listByNamespace(namespace)
                .stream().filter(ConfigSnapshot::current)
                .forEach(s -> currentByKey.put(s.configKey(), s));
        for (ConfigSnapshot snapshot : currentByKey.values()) {
            entries.add(new BundleEntry(snapshot.configKey(), snapshot.version(),
                    snapshot.content(), snapshot.contentMd5(), snapshot.sensitive()));
        }
        return ConfigBundleCodec.export(namespace, entries);
    }

    /**
     * bundle 导入：校验和 → 冲突分型（codec 纯函数）→ schema 门逐键校验（任一失败全不落库）→
     * add/overwrite 落新快照；敏感项导入为密文直存（不经发布加密，避免二次包裹）。
     */
    public ImportReport importBundle(String bundleJson, String conflictPolicy) {
        ConfigBundle bundle = ConfigBundleCodec.fromJson(bundleJson);
        Map<String, String> existing = new LinkedHashMap<>();
        snapshotService.listByNamespace(bundle.namespace())
                .stream().filter(ConfigSnapshot::current)
                .forEach(s -> existing.put(s.configKey(), s.content()));

        record StagedAction(String action, BundleEntry entry) {
        }
        List<StagedAction> staged = new ArrayList<>();
        ImportReport report = ConfigBundleCodec.importBundle(bundle, existing, conflictPolicy,
                (action, entry) -> staged.add(new StagedAction(action, entry)));
        if (!report.success()) {
            return report;
        }
        for (StagedAction stagedAction : staged) {
            if ("skip".equals(stagedAction.action())) {
                continue;
            }
            BundleEntry entry = stagedAction.entry();
            List<String> errors = schemaGate.check(bundle.namespace(), entry.content());
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException("bundle 导入被 schema 门拦截: " + entry.configKey()
                        + " -> " + String.join("; ", errors));
            }
        }
        for (StagedAction stagedAction : staged) {
            if ("skip".equals(stagedAction.action())) {
                continue;
            }
            BundleEntry entry = stagedAction.entry();
            snapshotService.publish(bundle.namespace(), entry.configKey(), entry.content(),
                    entry.contentMd5(), entry.sensitive(), "bundle-import",
                    "add".equals(stagedAction.action()) ? "新增导入" : "覆盖导入");
        }
        return report;
    }
}

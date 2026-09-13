package cn.chyuan.ai.domain.configcenter.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配置灰度路由（工单 0256 AG6，借鉴 Nacos 灰度 + 五期 AB08/AD5 stickiness 先例）—
 * 命名空间级规则：stable 全量 / gray 按租户白名单 + 稳定哈希百分比选灰度版本；
 * 同租户同命名空间恒得同版本（stickiness）。按租户选版属发布路由，非 AB 实验（0194-D4 沿用）。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class ConfigGrayRouter {

    public static final String MODE_STABLE = "stable";
    public static final String MODE_GRAY = "gray";

    /** 灰度规则值对象 */
    public record GrayRule(String namespace, String mode, Set<String> tenantWhitelist,
            int percentage, int grayVersion, boolean suspended) {

        public GrayRule {
            if (namespace == null || namespace.isBlank()) {
                throw new IllegalArgumentException("namespace 不能为空");
            }
            if (!MODE_STABLE.equals(mode) && !MODE_GRAY.equals(mode)) {
                throw new IllegalArgumentException("非法模式: " + mode);
            }
            if (percentage < 0 || percentage > 100) {
                throw new IllegalArgumentException("percentage 需在 [0,100]: " + percentage);
            }
            if (MODE_GRAY.equals(mode) && grayVersion <= 0) {
                throw new IllegalArgumentException("灰度模式需指定 grayVersion");
            }
            tenantWhitelist = tenantWhitelist == null ? Set.of() : Set.copyOf(tenantWhitelist);
        }
    }

    /** 解析结果：命中的版本与命中方式（white/percentage/stable） */
    public record Resolved(int version, String by, String ruleMode) {
    }

    private final Map<String, GrayRule> rules = new ConcurrentHashMap<>();
    /** 规则操作轨迹（审计补充：内存留档最近 100 条） */
    private final List<String> auditTrail = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** 设置/更新规则 */
    public GrayRule upsert(GrayRule rule) {
        rules.put(rule.namespace(), rule);
        audit("UPSERT ns=" + rule.namespace() + " mode=" + rule.mode()
                + " pct=" + rule.percentage() + " gray=" + rule.grayVersion());
        return rule;
    }

    /** 删除规则（回退全量 stable 语义） */
    public void remove(String namespace) {
        rules.remove(namespace);
        audit("REMOVE ns=" + namespace);
    }

    /** 暂停/恢复灰度（暂停时全量走 stable 版本；工单 0257 AG7 Suspended 态输入） */
    public GrayRule setSuspended(String namespace, boolean suspended) {
        GrayRule rule = rules.get(namespace);
        if (rule == null) {
            throw new IllegalArgumentException("命名空间无灰度规则: " + namespace);
        }
        GrayRule updated = new GrayRule(rule.namespace(), rule.mode(), rule.tenantWhitelist(),
                rule.percentage(), rule.grayVersion(), suspended);
        rules.put(namespace, updated);
        audit((suspended ? "SUSPEND " : "RESUME ") + "ns=" + namespace);
        return updated;
    }

    /**
     * 按租户解析生效版本：stable → stableVersion；灰度规则白名单/百分比命中 → grayVersion；
     * 暂停或未命中 → stableVersion。
     */
    public Resolved resolve(String namespace, String tenant, int stableVersion) {
        GrayRule rule = rules.get(namespace);
        if (rule == null || MODE_STABLE.equals(rule.mode()) || rule.suspended()) {
            return new Resolved(stableVersion, "stable", rule == null ? MODE_STABLE : rule.mode());
        }
        if (tenant != null && rule.tenantWhitelist().contains(tenant)) {
            return new Resolved(rule.grayVersion(), "white", rule.mode());
        }
        if (stickinessPercent(tenant, namespace) < rule.percentage()) {
            return new Resolved(rule.grayVersion(), "percentage", rule.mode());
        }
        return new Resolved(stableVersion, "stable", rule.mode());
    }

    /** 稳定哈希百分比（0-99）：同租户同命名空间恒定 */
    static int stickinessPercent(String tenant, String namespace) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((tenant + "|" + namespace).getBytes(StandardCharsets.UTF_8));
            int bucket = ((digest[0] & 0xFF) << 8 | (digest[1] & 0xFF)) % 100;
            return bucket;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public GrayRule ruleOf(String namespace) {
        return rules.get(namespace);
    }

    public Map<String, GrayRule> snapshot() {
        return Map.copyOf(rules);
    }

    /** 规则操作轨迹（最近 100 条） */
    public List<String> trail() {
        synchronized (auditTrail) {
            return List.copyOf(auditTrail.subList(Math.max(0, auditTrail.size() - 100), auditTrail.size()));
        }
    }

    private void audit(String line) {
        auditTrail.add(line);
        if (auditTrail.size() > 200) {
            auditTrail.subList(0, auditTrail.size() - 100).clear();
        }
        log.info("配置灰度规则: {}", line);
    }
}

package cn.chyuan.ai.domain.governance.service;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 特性开关目标定向评估纯函数（工单 0224 AD5，借鉴 Unleash targeting）—
 * 优先级：租户白名单 → 用户白名单 → 稳定哈希百分比（stickiness：同 键+租户+用户
 * 恒同结果）。白名单 CSV 配置化；percentage 0=纯开关语义（不切流）。
 *
 * @author chyuan
 */
public final class FlagTargetingEvaluator {

    /** 定向规则值对象（全空 = 无定向，退化为纯开关） */
    public record Targeting(String tenantWhitelistCsv, String userWhitelistCsv, int percentage) {
        public Targeting {
            tenantWhitelistCsv = tenantWhitelistCsv == null ? "" : tenantWhitelistCsv;
            userWhitelistCsv = userWhitelistCsv == null ? "" : userWhitelistCsv;
            if (percentage < 0) {
                percentage = 0;
            }
            if (percentage > 100) {
                percentage = 100;
            }
        }

        public boolean isEmpty() {
            return tenantWhitelistCsv.isBlank() && userWhitelistCsv.isBlank() && percentage == 0;
        }
    }

    private FlagTargetingEvaluator() {
    }

    /**
     * 评估：白名单命中即 true；否则按百分比稳定哈希；无定向规则返回 false（由调用方
     * 退回基础开关值）。
     */
    public static boolean evaluate(Targeting targeting, String flagKey, String tenantId, String userId) {
        if (targeting == null || targeting.isEmpty()) {
            return false;
        }
        if (inList(parseCsv(targeting.tenantWhitelistCsv()), tenantId)) {
            return true;
        }
        if (inList(parseCsv(targeting.userWhitelistCsv()), userId)) {
            return true;
        }
        if (targeting.percentage() <= 0) {
            return false;
        }
        return stableBucket(flagKey, tenantId, userId) < targeting.percentage();
    }

    /** 稳定桶：sha256(flagKey|tenantId|userId) → [0,100)，stickiness 同键恒同 */
    static int stableBucket(String flagKey, String tenantId, String userId) {
        String material = (flagKey == null ? "" : flagKey) + "|"
                + (tenantId == null ? "" : tenantId) + "|"
                + (userId == null ? "" : userId);
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            int value = ((digest[0] & 0xFF) << 24) | ((digest[1] & 0xFF) << 16)
                    | ((digest[2] & 0xFF) << 8) | (digest[3] & 0xFF);
            return Math.floorMod(value, 100);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static boolean inList(Set<String> list, String value) {
        return value != null && !value.isBlank() && list.contains(value.trim());
    }

    static Set<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split("[,，]")).map(String::trim)
                .filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }
}

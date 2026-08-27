package cn.chyuan.ai.types.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 虚拟密钥编解码工具（工单 0017）
 *
 * <p>vk- 凭证生成、SHA-256 哈希（入库存储形态，见 0011 决策③）与脱敏展示。
 * 纯 Java 实现，供 domain / infrastructure / trigger 各层共用。
 *
 * @author chyuan
 */
public final class KeyHashUtil {

    /** 虚拟密钥前缀（区别于存量 gw- 凭证） */
    public static final String VIRTUAL_KEY_PREFIX = "vk-";

    /** 凭证随机段长度（十六进制字符数，与存量 gw- 48 位对齐） */
    private static final int RANDOM_LENGTH = 48;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private KeyHashUtil() {
        // 工具类，禁止实例化
    }

    /**
     * 生成新的 vk- 凭证：vk- + 48 位随机十六进制
     */
    public static String generateVirtualKey() {
        StringBuilder sb = new StringBuilder(VIRTUAL_KEY_PREFIX.length() + RANDOM_LENGTH);
        sb.append(VIRTUAL_KEY_PREFIX);
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            sb.append(HEX[SECURE_RANDOM.nextInt(16)]);
        }
        return sb.toString();
    }

    /**
     * SHA-256 十六进制哈希（密钥入库/检索形态）
     */
    public static String sha256Hex(String value) {
        if (value == null) {
            return "";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    /**
     * 脱敏展示：保留前缀与前 4 位随机段（如 vk-abc1****）；
     * 非 vk- 凭证（存量 gw- 迁移名等场景）截断到前 8 字符。
     */
    public static String mask(String credential) {
        if (credential == null || credential.isBlank()) {
            return "";
        }
        if (credential.startsWith(VIRTUAL_KEY_PREFIX) && credential.length() > VIRTUAL_KEY_PREFIX.length() + 4) {
            return credential.substring(0, VIRTUAL_KEY_PREFIX.length() + 4) + "****";
        }
        return credential.length() <= 8 ? credential + "****" : credential.substring(0, 8) + "****";
    }
}

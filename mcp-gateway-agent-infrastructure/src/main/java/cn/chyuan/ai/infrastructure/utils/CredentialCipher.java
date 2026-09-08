package cn.chyuan.ai.infrastructure.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 凭证加密器（工单 0062，AES-GCM；APISIX encrypt_fields 口径）
 *
 * <p>密钥来自环境变量 {@code GOVERNANCE_ENC_KEY}（任意长度，SHA-256 派生 256 位密钥）；
 * 未配置时降级明文读写（启动 WARN 一次，生产必配——文档说明）；密文带 {@code enc:v1:} 前缀
 * （12 字节随机 IV + 密文 + 128 位 tag，Base64），读侧探测前缀自动解密，存量明文透明兼容。
 * 明文绝不因加解密异常外抛到响应面。
 *
 * @author chyuan
 */
@Slf4j
@Component
public class CredentialCipher {

    private static final String PREFIX = "enc:v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    private final boolean enabled;

    private volatile boolean warnedNoKey = false;

    public CredentialCipher(@Value("${GOVERNANCE_ENC_KEY:}") String encKey) {
        if (encKey == null || encKey.isBlank()) {
            this.key = null;
            this.enabled = false;
        } else {
            try {
                byte[] derived = MessageDigest.getInstance("SHA-256")
                        .digest(encKey.getBytes(StandardCharsets.UTF_8));
                this.key = new SecretKeySpec(derived, "AES");
                this.enabled = true;
            } catch (Exception e) {
                throw new IllegalStateException("加密密钥派生失败", e);
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 密文前缀探测（读侧兼容存量明文） */
    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /**
     * 加密：未启用或空值原样返回；已加密值幂等直通。
     */
    public String encrypt(String plain) {
        if (!enabled || plain == null || plain.isBlank() || isEncrypted(plain)) {
            warnOnceIfDisabled();
            return plain;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(cipherText, 0, packed, iv.length, cipherText.length);
            return PREFIX + Base64.getEncoder().encodeToString(packed);
        } catch (Exception e) {
            // 加密失败宁可明文落库（降级可用）也不阻断写路径；日志告警
            log.error("凭证加密失败（降级明文落库）");
            return plain;
        }
    }

    /**
     * 解密：密文前缀才解；未启用或明文原样返回；解密失败返回空串（不外抛、不泄露）。
     */
    public String decrypt(String stored) {
        if (stored == null || !isEncrypted(stored)) {
            return stored;
        }
        if (!enabled) {
            log.error("凭证为密文但未配置 GOVERNANCE_ENC_KEY（返回空串，请配置后重启）");
            return "";
        }
        try {
            byte[] packed = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, packed, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(packed, IV_BYTES, packed.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("凭证解密失败（密钥不符或数据损坏，返回空串）");
            return "";
        }
    }

    private void warnOnceIfDisabled() {
        if (!enabled && !warnedNoKey) {
            warnedNoKey = true;
            log.warn("未配置 GOVERNANCE_ENC_KEY —— 凭证明文落库（生产环境必须配置，见 docs/03-mcp-gateway-agent/14）");
        }
    }
}

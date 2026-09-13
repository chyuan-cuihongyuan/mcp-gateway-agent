package cn.chyuan.ai.domain.configcenter.service;

import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 敏感配置信封加密（工单 0254 AG4，借鉴 Vault envelope encryption）—
 * 随机 DEK 加密内容、KEK 加密 DEK，密文格式 enc-v1:iv:wrappedDek:ciphertext（Base64 三段）；
 * KEK 经 {@link KeyRing} 端口供给（本地口令派生，不引入外部 KMS）。篡改检测由 GCM 校验标签承担。
 *
 * @author chyuan
 */
@Service
public class EnvelopeCipher {

    /** 密文格式前缀（isToken 判定依据；敏感项存储形态） */
    public static final String TOKEN_PREFIX = "enc-v1:";

    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int DEK_BITS = 128;
    private static final int PBKDF2_ITERATIONS = 65_536;

    /** KEK 供给端口（infrastructure 提供本地实现；测试可注入固定口令） */
    public interface KeyRing {

        /** KEK 口令（派生 KEK 用；非空） */
        String kekSecret();
    }

    private final KeyRing keyRing;
    private final SecureRandom random = new SecureRandom();

    public EnvelopeCipher(KeyRing keyRing) {
        if (keyRing == null || keyRing.kekSecret() == null || keyRing.kekSecret().isBlank()) {
            throw new IllegalArgumentException("KeyRing 口令不能为空");
        }
        this.keyRing = keyRing;
    }

    /** 明文加密为 enc-v1 令牌 */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            random.nextBytes(iv);
            SecretKey dek = generateDek();
            byte[] ciphertext = aesGcm(dek, iv, plaintext.getBytes(StandardCharsets.UTF_8), Cipher.ENCRYPT_MODE);
            byte[] wrappedDek = kekCipher(dek.getEncoded(), Cipher.ENCRYPT_MODE);
            Base64.Encoder b64 = Base64.getEncoder();
            return TOKEN_PREFIX + b64.encodeToString(iv) + ":" + b64.encodeToString(wrappedDek)
                    + ":" + b64.encodeToString(ciphertext);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("信封加密失败: " + e.getMessage(), e);
        }
    }

    /** enc-v1 令牌解密为明文（篡改/口令不符抛 IllegalArgumentException） */
    public String decrypt(String token) {
        if (!isToken(token)) {
            throw new IllegalArgumentException("不是 enc-v1 密文格式");
        }
        String[] parts = token.substring(TOKEN_PREFIX.length()).split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException("enc-v1 密文段数不对");
        }
        try {
            Base64.Decoder b64 = Base64.getDecoder();
            byte[] iv = b64.decode(parts[0]);
            byte[] wrappedDek = b64.decode(parts[1]);
            byte[] ciphertext = b64.decode(parts[2]);
            SecretKey dek = new SecretKeySpec(kekCipher(wrappedDek, Cipher.DECRYPT_MODE), "AES");
            return new String(aesGcm(dek, iv, ciphertext, Cipher.DECRYPT_MODE), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalArgumentException("解密失败（密文被篡改或口令不符）", e);
        }
    }

    /** 是否 enc-v1 密文格式 */
    public static boolean isToken(String value) {
        return value != null && value.startsWith(TOKEN_PREFIX);
    }

    private SecretKey generateDek() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(DEK_BITS);
        return generator.generateKey();
    }

    /** 内容层 AES-GCM */
    private byte[] aesGcm(SecretKey key, byte[] iv, byte[] data, int mode) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(data);
    }

    /** KEK 层 AES-ECB 仅用于包裹 16 字节 DEK（单块，GCM 会被 IV 复用限制，包封层数据量固定） */
    private byte[] kekCipher(byte[] dekEncoded, int mode) throws Exception {
        byte[] salt = "config-center-kek".getBytes(StandardCharsets.UTF_8);
        SecretKey kek = deriveKek(keyRing.kekSecret(), salt);
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(mode, kek);
        return cipher.doFinal(dekEncoded);
    }

    /** PBKDF2 口令派生 KEK */
    private SecretKey deriveKek(String secret, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(secret.toCharArray(), salt, PBKDF2_ITERATIONS, DEK_BITS);
        byte[] keyBytes = SecretKeyFactoryHolder.factory().generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }

    /** 延迟持有工厂（避免实例字段） */
    private static final class SecretKeyFactoryHolder {
        private static final String ALGORITHM = "PBKDF2WithHmacSHA256";

        static javax.crypto.SecretKeyFactory factory() throws Exception {
            return javax.crypto.SecretKeyFactory.getInstance(ALGORITHM);
        }
    }

    /** SHA-256 十六进制（快照 contentMd5 之外 bundle 校验和共用） */
    public static String sha256Hex(String data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

package cn.chyuan.ai.domain.vaultkernel.service;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;

/**
 * 信封加密（工单 0565 BP1，vault transit/JCA 思想）。
 * 数据密钥 DEK 随机生成/主密钥 KEK 以 AES-GCM（随机 12B IV+128b authTag）包裹/
 * 解包还原往返/密文信封格式 [4B 版本 BE][12B IV][密文+tag]/篡改密文 AEAD 校验拒绝。
 * JDK JCA 实现，零新依赖。
 */
public final class EnvelopeCrypto {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORM = "AES/GCM/NoPadding";

    private EnvelopeCrypto() {
    }

    /** DEK 生成（默认 32 字节） */
    public static byte[] generateDek(int bytes) {
        if (bytes < 16 || bytes > 64) {
            throw new IllegalArgumentException("DEK 长度须在 [16,64]: " + bytes);
        }
        byte[] dek = new byte[bytes];
        new SecureRandom().nextBytes(dek);
        return dek;
    }

    /** KEK 规格化（16/24/32 字节 AES 密钥） */
    public static SecretKeySpec kek(byte[] bytes) {
        if (bytes == null || (bytes.length != 16 && bytes.length != 24 && bytes.length != 32)) {
            throw new IllegalArgumentException("KEK 须 16/24/32 字节");
        }
        return new SecretKeySpec(bytes, "AES");
    }

    /** 包裹：输出 [12B IV][密文+tag]（版本由外层信封携带） */
    public static byte[] wrap(byte[] dek, SecretKeySpec kek) {
        try {
            byte[] iv = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, kek, new javax.crypto.spec.GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(dek);
            byte[] out = new byte[IV_BYTES + sealed.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(sealed, 0, out, IV_BYTES, sealed.length);
            return out;
        } catch (InvalidKeyException | NoSuchAlgorithmException | NoSuchPaddingException
                | java.security.InvalidAlgorithmParameterException
                | javax.crypto.IllegalBlockSizeException | javax.crypto.BadPaddingException e) {
            throw new IllegalArgumentException("包裹失败: " + e.getMessage(), e);
        }
    }

    /** 解包（AEAD tag 校验失败视为密文篡改拒绝） */
    public static byte[] unwrap(byte[] wrapped, SecretKeySpec kek) {
        if (wrapped == null || wrapped.length <= IV_BYTES) {
            throw new IllegalArgumentException("包裹格式非法");
        }
        try {
            byte[] iv = java.util.Arrays.copyOfRange(wrapped, 0, IV_BYTES);
            byte[] sealed = java.util.Arrays.copyOfRange(wrapped, IV_BYTES, wrapped.length);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, kek, new javax.crypto.spec.GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(sealed);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new IllegalArgumentException("密文篡改拒绝（AEAD 校验失败）", e);
        } catch (InvalidKeyException | NoSuchAlgorithmException | NoSuchPaddingException
                | java.security.InvalidAlgorithmParameterException
                | javax.crypto.IllegalBlockSizeException | javax.crypto.BadPaddingException e) {
            throw new IllegalArgumentException("解包失败: " + e.getMessage(), e);
        }
    }
}

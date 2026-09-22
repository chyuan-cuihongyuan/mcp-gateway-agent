package cn.chyuan.ai.domain.vaultkernel.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 派生密钥（工单 0569 BP5，RFC 5869 HKDF 思想，HMAC-SHA256）。
 * Extract（盐+输入密钥材料→PRK）/Expand（PRK+上下文 info→定长 OKM）/
 * 同根不同用途子密钥不同/同参数派生确定性/输出长度超 255×32 拒绝。
 */
public final class HkdfDerive {

    private static final int HASH_BYTES = 32;

    private HkdfDerive() {
    }

    /** Extract：PRK = HMAC-SHA256(salt, ikm)（空盐按全零盐） */
    public static byte[] extract(byte[] salt, byte[] ikm) {
        byte[] effectiveSalt = salt == null || salt.length == 0 ? new byte[HASH_BYTES] : salt;
        return hmac(effectiveSalt, ikm);
    }

    /** Expand：T(1..n) 连缀截取 length 字节；length>255×32 拒绝 */
    public static byte[] expand(byte[] prk, byte[] info, int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("输出长度须为正");
        }
        if (length > 255 * HASH_BYTES) {
            throw new IllegalArgumentException("输出长度超上界 255×32: " + length);
        }
        byte[] okm = new byte[length];
        byte[] t = new byte[0];
        int generated = 0;
        byte counter = 1;
        while (generated < length) {
            byte[] input = concat(t, info, new byte[]{counter});
            t = hmac(prk, input);
            int copy = Math.min(HASH_BYTES, length - generated);
            System.arraycopy(t, 0, okm, generated, copy);
            generated += copy;
            counter++;
        }
        return okm;
    }

    /** 一步派生：extract+expand（上下文 info 绑定用途） */
    public static byte[] derive(byte[] ikm, byte[] salt, byte[] info, int length) {
        return expand(extract(salt, ikm), info, length);
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalArgumentException("HMAC 失败: " + e.getMessage(), e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b, byte[] c) {
        byte[] out = new byte[a.length + b.length + c.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        System.arraycopy(c, 0, out, a.length + b.length, c.length);
        return out;
    }
}

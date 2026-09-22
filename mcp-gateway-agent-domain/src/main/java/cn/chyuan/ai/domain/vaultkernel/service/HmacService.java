package cn.chyuan.ai.domain.vaultkernel.service;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC 签名与验证（工单 0568 BP4，vault HMAC 思想）。
 * HMAC-SHA256 签名（密钥版本绑定：信封 [4B 版本 BE][32B mac]）/
 * 验证恒时比较（MessageDigest.isEqual 防时序侧信道）/
 * 篡改载荷或密钥错版拒绝/密钥未注册拒绝。
 */
public final class HmacService {

    public static final int MAC_BYTES = 32;

    /** 签名信封：密钥版本 + MAC */
    public record Signature(int version, byte[] mac) {
    }

    private final KeyRing ring;

    public HmacService(KeyRing ring) {
        if (ring == null) {
            throw new IllegalArgumentException("密钥环不得为 null");
        }
        this.ring = ring;
    }

    /** 签名（用当前版本 KEK 派生面——直接以 KEK 作 HMAC 密钥） */
    public Signature sign(byte[] data) {
        int version = ring.currentVersion();
        byte[] mac = mac(data, ring.key(version));
        return new Signature(version, mac);
    }

    /** 验证：恒时比较；密钥版本从信封读取（错版/未注册拒绝） */
    public boolean verify(byte[] data, Signature signature) {
        SecretKeySpec key;
        try {
            key = ring.key(signature.version());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("签名密钥未注册: v" + signature.version(), e);
        }
        byte[] expected = mac(data, key);
        return MessageDigest.isEqual(expected, signature.mac());
    }

    private byte[] mac(byte[] data, SecretKeySpec key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalArgumentException("HMAC 计算失败: " + e.getMessage(), e);
        }
    }

    /** 信封编解码：[4B 版本 BE][32B mac] */
    public byte[] encode(Signature signature) {
        return ByteBuffer.allocate(4 + MAC_BYTES).putInt(signature.version())
                .put(signature.mac()).array();
    }

    public Signature decode(byte[] envelope) {
        if (envelope == null || envelope.length != 4 + MAC_BYTES) {
            throw new IllegalArgumentException("签名信封格式非法");
        }
        ByteBuffer buffer = ByteBuffer.wrap(envelope);
        int version = buffer.getInt();
        byte[] mac = new byte[MAC_BYTES];
        buffer.get(mac);
        return new Signature(version, mac);
    }
}

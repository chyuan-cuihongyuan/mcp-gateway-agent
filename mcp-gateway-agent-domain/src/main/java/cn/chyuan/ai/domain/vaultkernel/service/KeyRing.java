package cn.chyuan.ai.domain.vaultkernel.service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.spec.SecretKeySpec;

/**
 * 密钥版本轮换（工单 0566 BP2，vault key ring 思想）。
 * 密钥版本链单调递增/生成数据密钥恒用最新版本包裹/解包按信封版本回退/
 * 当前版本指针只前进/轮换事件有序登记。信封 = 版本号 + KEK 包裹的 DEK。
 */
public final class KeyRing {

    /** DEK 信封：包裹时使用的 KEK 版本 + 包裹体 */
    public record Envelope(int version, byte[] wrapped) {
    }

    /** 生成数据密钥：明文 DEK（数据侧即刻使用）+ KEK 包裹信封（持久化） */
    public record Generated(byte[] plaintextDek, Envelope envelope) {
    }

    private final String keyId;
    private final Map<Integer, SecretKeySpec> versions = new TreeMap<>();
    private final List<String> events = new ArrayList<>();
    private int current;

    public KeyRing(String keyId) {
        if (keyId == null || keyId.isEmpty()) {
            throw new IllegalArgumentException("key id 不得为空");
        }
        this.keyId = keyId;
        rotate();
    }

    public String keyId() {
        return keyId;
    }

    /** 轮换：生成新版本 KEK 并前移指针（返回新版本号） */
    public int rotate() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        versions.put(++current, EnvelopeCrypto.kek(bytes));
        events.add("ROTATE v" + current);
        return current;
    }

    public int currentVersion() {
        return current;
    }

    public SecretKeySpec key(int version) {
        SecretKeySpec key = versions.get(version);
        if (key == null) {
            throw new IllegalArgumentException("未注册版本: v" + version);
        }
        return key;
    }

    public List<String> events() {
        return List.copyOf(events);
    }

    /** 生成数据密钥：DEK 以当前版本 KEK 包裹 */
    public Generated generateDataKey() {
        byte[] dek = EnvelopeCrypto.generateDek(32);
        return new Generated(dek, new Envelope(current, EnvelopeCrypto.wrap(dek, versions.get(current))));
    }

    /** 解包数据密钥（按信封版本回退对应 KEK；未注册版本拒绝） */
    public byte[] unwrapDataKey(Envelope envelope) {
        SecretKeySpec kek = versions.get(envelope.version());
        if (kek == null) {
            throw new IllegalArgumentException("信封版本未注册: v" + envelope.version());
        }
        return EnvelopeCrypto.unwrap(envelope.wrapped(), kek);
    }

    /**
     * 重包裹到当前版本：旧 KEK 解包 DEK → 新 KEK 重包裹，信封版本随目标；
     * DEK 字节用后即清零，业务明文全程不参与。
     */
    public Envelope rewrap(Envelope envelope, int targetVersion) {
        SecretKeySpec source = versions.get(envelope.version());
        SecretKeySpec target = versions.get(targetVersion);
        if (source == null || target == null) {
            throw new IllegalArgumentException("版本未注册: v" + envelope.version() + "→v" + targetVersion);
        }
        byte[] dek = EnvelopeCrypto.unwrap(envelope.wrapped(), source);
        try {
            return new Envelope(targetVersion, EnvelopeCrypto.wrap(dek, target));
        } finally {
            java.util.Arrays.fill(dek, (byte) 0);
        }
    }
}

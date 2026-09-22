package cn.chyuan.ai.domain.vaultkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * 动态密钥租约（工单 0570 BP6，vault dynamic secret lease 思想）。
 * 动态密钥签发绑定 TTL 与用途元数据/续租延长/到期撤销（时钟端口）/
 * 撤销后使用拒绝/租约清单有序。window-kernel 时钟端口同款注入。
 */
public final class DynamicKeyLease {

    /** 租约（密钥值一次性签发即持于持有方，租约只管生命周期与用途） */
    public record Lease(String id, String purpose, long issuedAt, long expiresAt, String status) {
    }

    public static final String ACTIVE = "ACTIVE";
    public static final String REVOKED = "REVOKED";
    public static final String EXPIRED = "EXPIRED";

    private final java.util.function.LongSupplier clock;
    private final TreeMap<String, Lease> leases = new TreeMap<>();
    private int sequence;

    public DynamicKeyLease(java.util.function.LongSupplier clock) {
        if (clock == null) {
            throw new IllegalArgumentException("时钟端口不得为 null");
        }
        this.clock = clock;
    }

    /** 签发动态密钥租约（TTL 为正；用途元数据绑定） */
    public Lease issue(String purpose, long ttlMillis) {
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("TTL 须为正");
        }
        long now = clock.getAsLong();
        Lease lease = new Lease("lease-" + (++sequence), purpose, now, now + ttlMillis, ACTIVE);
        leases.put(lease.id(), lease);
        return lease;
    }

    /** 续租：仅 ACTIVE 且未过期可续（撤销/过期拒绝），到期时间延长 */
    public Lease renew(String id, long extendByMillis) {
        Lease lease = requireActive(id);
        Lease renewed = new Lease(lease.id(), lease.purpose(), lease.issuedAt(),
                lease.expiresAt() + extendByMillis, ACTIVE);
        leases.put(id, renewed);
        return renewed;
    }

    /** 撤销（幂等：已撤销直接返回） */
    public Lease revoke(String id) {
        Lease lease = leases.get(id);
        if (lease == null) {
            throw new IllegalArgumentException("租约不存在: " + id);
        }
        if (REVOKED.equals(lease.status())) {
            return lease;
        }
        Lease revoked = new Lease(lease.id(), lease.purpose(), lease.issuedAt(), lease.expiresAt(), REVOKED);
        leases.put(id, revoked);
        return revoked;
    }

    /** 使用判定：ACTIVE 且时钟未到期；过期自动转 EXPIRED 并拒绝 */
    public boolean usable(String id) {
        Lease lease = leases.get(id);
        if (lease == null) {
            throw new IllegalArgumentException("租约不存在: " + id);
        }
        if (REVOKED.equals(lease.status())) {
            return false;
        }
        if (clock.getAsLong() > lease.expiresAt()) {
            leases.put(id, new Lease(lease.id(), lease.purpose(), lease.issuedAt(),
                    lease.expiresAt(), EXPIRED));
            return false;
        }
        return true;
    }

    /** 活跃租约清单（id 字典序） */
    public List<Lease> activeLeases() {
        List<Lease> out = new ArrayList<>();
        for (Lease lease : leases.values()) {
            if (ACTIVE.equals(lease.status()) && usable(lease.id())) {
                out.add(lease);
            }
        }
        return List.copyOf(out);
    }

    private Lease requireActive(String id) {
        Lease lease = leases.get(id);
        if (lease == null) {
            throw new IllegalArgumentException("租约不存在: " + id);
        }
        if (!ACTIVE.equals(lease.status())) {
            throw new IllegalArgumentException("租约非活跃: " + id + " (" + lease.status() + ")");
        }
        return lease;
    }
}

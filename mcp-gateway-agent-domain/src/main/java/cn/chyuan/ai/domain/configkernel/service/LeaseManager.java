package cn.chyuan.ai.domain.configkernel.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * lease 租约（工单 0466 BD3，etcd lease 思想）。
 * TTL 租约注册/键绑定租约/到期撤销级联删除绑定键/续租保活；
 * 时钟端口注入（纯函数无真实时钟）。租约 id 全局单调。
 */
public class LeaseManager {

    /** 时钟端口：测试注入确定时刻 */
    @FunctionalInterface
    public interface ClockPort {
        long nowMillis();
    }

    /** 租约快照 */
    public record Lease(long id, long ttlMillis, long expiresAt, Set<String> boundKeys) {
    }

    /** 过期撤销结果：租约 id + 需级联删除的绑定键 */
    public record RevokedLease(long id, List<String> cascadeDeleteKeys) {
    }

    private final ClockPort clock;
    private final Map<Long, long[]> leases = new LinkedHashMap<>();
    private final Map<Long, LinkedHashSet<String>> boundKeys = new LinkedHashMap<>();
    private long nextLeaseId;

    public LeaseManager(ClockPort clock) {
        this.clock = clock;
    }

    /** 注册 TTL 租约 */
    public synchronized long grant(long ttlMillis) {
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("TTL 须 > 0: " + ttlMillis);
        }
        long id = ++nextLeaseId;
        leases.put(id, new long[]{ttlMillis, clock.nowMillis() + ttlMillis});
        boundKeys.put(id, new LinkedHashSet<>());
        return id;
    }

    /** 续租保活：到期时刻重置为 now + TTL；租约不存在或已撤销时拒绝 */
    public synchronized long keepAlive(long leaseId) {
        long[] lease = requireLiveLease(leaseId);
        lease[1] = clock.nowMillis() + lease[0];
        return lease[1];
    }

    /** 键绑定租约（绑定到已撤销租约拒绝） */
    public synchronized void bindKey(long leaseId, String key) {
        requireLiveLease(leaseId);
        boundKeys.get(leaseId).add(key);
    }

    /** 主动撤销：返回需级联删除的绑定键 */
    public synchronized List<String> revoke(long leaseId) {
        if (!leases.containsKey(leaseId)) {
            throw new IllegalArgumentException("租约不存在: " + leaseId);
        }
        List<String> keys = new ArrayList<>(boundKeys.get(leaseId));
        leases.remove(leaseId);
        boundKeys.remove(leaseId);
        return keys;
    }

    /** 到期检测：仅报告已过期且未撤销的租约 */
    public synchronized List<Long> expiredLeases() {
        long now = clock.nowMillis();
        List<Long> expired = new ArrayList<>();
        for (Map.Entry<Long, long[]> entry : leases.entrySet()) {
            if (entry.getValue()[1] <= now) {
                expired.add(entry.getKey());
            }
        }
        return expired;
    }

    /** 到期撤销级联：撤销全部过期租约并返回各自绑定键（供上层删 MVCC 键） */
    public synchronized List<RevokedLease> revokeExpired() {
        List<RevokedLease> revoked = new ArrayList<>();
        for (Long leaseId : expiredLeases()) {
            revoked.add(new RevokedLease(leaseId, revoke(leaseId)));
        }
        return revoked;
    }

    /** 活跃租约快照（按 id 升序） */
    public synchronized List<Lease> snapshot() {
        List<Long> ids = new ArrayList<>(leases.keySet());
        Collections.sort(ids);
        List<Lease> result = new ArrayList<>();
        for (Long id : ids) {
            long[] lease = leases.get(id);
            result.add(new Lease(id, lease[0], lease[1], Set.copyOf(boundKeys.get(id))));
        }
        return result;
    }

    /** 恢复支持：按原 id/TTL/到期/绑定键重建租约（SnapshotCodec 专用） */
    synchronized void restoreLease(long id, long ttlMillis, long expiresAt, java.util.Collection<String> keys) {
        if (id > nextLeaseId) {
            nextLeaseId = id;
        }
        leases.put(id, new long[]{ttlMillis, expiresAt});
        boundKeys.put(id, new LinkedHashSet<>(keys));
    }

    private long[] requireLiveLease(long leaseId) {
        long[] lease = leases.get(leaseId);
        if (lease == null) {
            throw new IllegalArgumentException("租约不存在或已撤销: " + leaseId);
        }
        return lease;
    }
}

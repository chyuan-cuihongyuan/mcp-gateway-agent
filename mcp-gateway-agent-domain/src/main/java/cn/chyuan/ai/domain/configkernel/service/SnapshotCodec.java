package cn.chyuan.ai.domain.configkernel.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * 快照与恢复（工单 0469 BD6，etcd snapshot 思想）。
 * 全量快照导出（键版本链 + 租约 + revision 游标）/校验和断言/重建等价
 * （恢复后 revision 续写与历史读取与原实例一致）。
 */
public class SnapshotCodec {

    /** 快照条目：键 + 完整版本链 */
    public record KvEntry(String key, List<MvccStore.VersionedValue> chain) {
    }

    /** 租约快照 */
    public record LeaseEntry(long id, long ttlMillis, long expiresAt, List<String> boundKeys) {
    }

    /** 全量快照：revision 游标 + 键条目 + 租约 + 校验和 */
    public record Snapshot(long revision, List<KvEntry> entries, List<LeaseEntry> leases, String checksum) {
    }

    /** 恢复产物 */
    public record Restored(MvccStore store, LeaseManager leases) {
    }

    /** 导出全量快照（键字典序、租约 id 升序，校验和确定性） */
    public Snapshot export(MvccStore store, LeaseManager leases) {
        List<KvEntry> entries = store.keys().stream()
                .map(key -> new KvEntry(key, store.versionChain(key)))
                .toList();
        List<LeaseEntry> leaseEntries = leases.snapshot().stream()
                .map(lease -> new LeaseEntry(lease.id(), lease.ttlMillis(), lease.expiresAt(),
                        new ArrayList<>(lease.boundKeys())))
                .toList();
        String checksum = checksum(store.currentRevision(), entries, leaseEntries);
        return new Snapshot(store.currentRevision(), entries, leaseEntries, checksum);
    }

    /** 校验和断言：不符抛出（防篡改/防截断） */
    public void requireValid(Snapshot snapshot) {
        String expected = checksum(snapshot.revision(), snapshot.entries(), snapshot.leases());
        if (!expected.equals(snapshot.checksum())) {
            throw new IllegalStateException("快照校验和不符: 期望 " + expected + " 实际 " + snapshot.checksum());
        }
    }

    /** 恢复：重建等价的 MvccStore + LeaseManager（revision 游标续写、历史可读） */
    public Restored restore(Snapshot snapshot) {
        requireValid(snapshot);
        MvccStore store = new MvccStore();
        for (KvEntry entry : snapshot.entries()) {
            for (MvccStore.VersionedValue version : entry.chain()) {
                store.restoreVersion(entry.key(), version);
            }
        }
        LeaseManager leases = new LeaseManager(() -> 0L);
        for (LeaseEntry lease : snapshot.leases()) {
            leases.restoreLease(lease.id(), lease.ttlMillis(), lease.expiresAt(), lease.boundKeys());
        }
        store.restoreRevisionCursor(snapshot.revision());
        return new Restored(store, leases);
    }

    /** 确定性校验和：canonical 序列化后 SHA-256 前 16 字节 hex */
    String checksum(long revision, List<KvEntry> entries, List<LeaseEntry> leases) {
        StringBuilder canonical = new StringBuilder();
        canonical.append("rev=").append(revision).append('\n');
        for (KvEntry entry : entries) {
            canonical.append('K').append(entry.key()).append('=');
            for (MvccStore.VersionedValue version : entry.chain()) {
                canonical.append(version.revision()).append(':')
                        .append(version.tombstone() ? "~" : version.value()).append(';');
            }
            canonical.append('\n');
        }
        for (LeaseEntry lease : leases) {
            canonical.append('L').append(lease.id()).append(':').append(lease.ttlMillis())
                    .append(':').append(lease.expiresAt()).append('=');
            for (String key : lease.boundKeys()) {
                canonical.append(key).append(',');
            }
            canonical.append('\n');
        }
        return sha256Hex(canonical.toString());
    }

    private String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                hex.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(hash[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}

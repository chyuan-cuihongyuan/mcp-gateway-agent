package cn.chyuan.ai.domain.configkernel.service;

import java.util.HashSet;
import java.util.Set;

/**
 * compact 压缩（工单 0467 BD4，etcd compact 思想）。
 * 压缩到指定 revision 清理历史（保留 ≤ 压缩点的最新版本作为基线）
 * /墓碑基线整键清除/被活跃租约绑定键的历史保护（整键保留不压缩）；
 * 压缩游标只前进（重复压缩幂等）；压缩点之下的历史读取拒绝。
 */
public class Compactor {

    private final MvccStore store;
    private final Set<String> leaseBoundKeys = new HashSet<>();
    private long compactedRevision;

    public Compactor(MvccStore store) {
        this.store = store;
    }

    /** 同步租约绑定键集合（活跃租约持有的键受历史保护） */
    public synchronized void protectKeys(Set<String> boundKeys) {
        leaseBoundKeys.clear();
        if (boundKeys != null) {
            leaseBoundKeys.addAll(boundKeys);
        }
    }

    /**
     * 压缩到指定 revision：
     * 非租约保护键 → 移除 < compactRevision 的全部历史；基线（≤ 点的最新版本）为墓碑则整键清除；
     * 租约保护键 → 整键历史保留。
     *
     * @return 清除的版本数
     */
    public synchronized long compactTo(long compactRevision) {
        if (compactRevision <= 0) {
            throw new IllegalArgumentException("压缩点须 > 0: " + compactRevision);
        }
        if (compactRevision > store.currentRevision()) {
            throw new IllegalArgumentException("压缩点超出当前 revision " + store.currentRevision() + ": " + compactRevision);
        }
        if (compactRevision < compactedRevision) {
            return 0L;
        }
        long removed = 0;
        for (String key : store.keys()) {
            if (leaseBoundKeys.contains(key)) {
                continue;
            }
            removed += compactKey(key, compactRevision);
        }
        compactedRevision = compactRevision;
        store.markCompacted(compactRevision);
        return removed;
    }

    /** 压缩游标（已压缩到的最高 revision） */
    public synchronized long compactedRevision() {
        return compactedRevision;
    }

    private long compactKey(String key, long compactRevision) {
        var chain = store.versionChain(key);
        if (chain.isEmpty() || chain.get(chain.size() - 1).revision() < compactRevision) {
            return 0L;
        }
        var baseline = baselineAt(chain, compactRevision);
        if (baseline == null) {
            return 0L;
        }
        if (baseline.tombstone()) {
            return store.dropKey(key);
        }
        return store.dropVersionsBefore(key, compactRevision, baseline.revision());
    }

    private MvccStore.VersionedValue baselineAt(java.util.List<MvccStore.VersionedValue> chain, long compactRevision) {
        MvccStore.VersionedValue baseline = null;
        for (MvccStore.VersionedValue version : chain) {
            if (version.revision() <= compactRevision) {
                baseline = version;
            } else {
                break;
            }
        }
        return baseline;
    }
}

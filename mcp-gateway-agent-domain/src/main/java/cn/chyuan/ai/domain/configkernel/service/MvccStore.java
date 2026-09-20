package cn.chyuan.ai.domain.configkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MVCC 多版本键值（工单 0464 BD1，etcd mvcc 子集思想）。
 * 全局单调 revision（put/delete 各分配一个）/键版本链（同键多版本按 revision 有序）
 * /按 revision 读取历史版本/删除为墓碑版本（带删除标记）；
 * revision 空间耗尽与非法 revision 拒绝。纯函数内核，无外部依赖。
 */
public class MvccStore {

    /** 单个版本：revision + 值 + 墓碑标记 */
    public record VersionedValue(long revision, String value, boolean tombstone) {
    }

    /** 历史读取结果：命中为值，墓碑或不存在为空 */
    public record HistoryResult(boolean present, String value, long revision) {
    }

    private final Map<String, TreeMap<Long, VersionedValue>> chains = new ConcurrentHashMap<>();
    private long currentRevision;
    private long compactedBelow;

    /** 写入：分配新 revision 追加版本链尾部 */
    public synchronized long put(String key, String value) {
        ensureRevisionSpace();
        long revision = nextRevision();
        chain(key).put(revision, new VersionedValue(revision, value, false));
        return revision;
    }

    /** 删除：墓碑版本；键不存在时为幂等 no-op（返回 0 表示未分配 revision） */
    public synchronized long delete(String key) {
        TreeMap<Long, VersionedValue> chain = chains.get(key);
        if (chain == null || chain.isEmpty()) {
            return 0L;
        }
        ensureRevisionSpace();
        long revision = nextRevision();
        chain.put(revision, new VersionedValue(revision, null, true));
        return revision;
    }

    /** 读最新值：最新版本为墓碑视为不存在 */
    public synchronized HistoryResult get(String key) {
        TreeMap<Long, VersionedValue> chain = chains.get(key);
        if (chain == null || chain.isEmpty()) {
            return new HistoryResult(false, null, 0L);
        }
        VersionedValue latest = chain.lastEntry().getValue();
        if (latest.tombstone()) {
            return new HistoryResult(false, null, latest.revision());
        }
        return new HistoryResult(true, latest.value(), latest.revision());
    }

    /** 按 revision 读历史：取 ≤ revision 的最近版本；该版本为墓碑则视为已删除 */
    public synchronized HistoryResult getAt(String key, long revision) {
        requireLegalRevision(revision);
        if (revision < compactedBelow) {
            throw new IllegalArgumentException("revision " + revision + " 已被压缩（压缩游标 " + compactedBelow + "）");
        }
        TreeMap<Long, VersionedValue> chain = chains.get(key);
        if (chain == null) {
            return new HistoryResult(false, null, 0L);
        }
        Map.Entry<Long, VersionedValue> floor = chain.floorEntry(revision);
        if (floor == null) {
            return new HistoryResult(false, null, 0L);
        }
        VersionedValue version = floor.getValue();
        if (version.tombstone()) {
            return new HistoryResult(false, null, version.revision());
        }
        return new HistoryResult(true, version.value(), version.revision());
    }

    /** 键版本链（按 revision 升序只读快照） */
    public synchronized List<VersionedValue> versionChain(String key) {
        TreeMap<Long, VersionedValue> chain = chains.get(key);
        if (chain == null) {
            return List.of();
        }
        return new ArrayList<>(chain.values());
    }

    /** 全部键（字典序） */
    public synchronized List<String> keys() {
        return new ArrayList<>(chains.keySet()).stream().sorted().toList();
    }

    public synchronized long currentRevision() {
        return currentRevision;
    }

    /** 恢复支持：按原版本写入（不推进游标，SnapshotCodec 专用） */
    synchronized void restoreVersion(String key, VersionedValue version) {
        chain(key).put(version.revision(), version);
    }

    /** 恢复支持：回填 revision 游标（只允许前进） */
    synchronized void restoreRevisionCursor(long revision) {
        if (revision < currentRevision) {
            throw new IllegalArgumentException("revision 游标不可回退: " + currentRevision + " → " + revision);
        }
        currentRevision = revision;
    }

    /** 压缩支持：推进压缩水位（只前进），水位之下历史读取拒绝 */
    synchronized void markCompacted(long revision) {
        if (revision > compactedBelow) {
            compactedBelow = revision;
        }
    }

    /** 压缩支持：整键清除（Compactor 专用，revision 游标不变） */
    synchronized int dropKey(String key) {
        TreeMap<Long, VersionedValue> chain = chains.remove(key);
        return chain == null ? 0 : chain.size();
    }

    /** 压缩支持：清除键上 < compactRevision 且非基线的版本，返回清除数 */
    synchronized int dropVersionsBefore(String key, long compactRevision, long baselineRevision) {
        TreeMap<Long, VersionedValue> chain = chains.get(key);
        if (chain == null) {
            return 0;
        }
        int before = chain.size();
        chain.subMap(0L, true, compactRevision, true).keySet().removeIf(rev -> rev != baselineRevision);
        return before - chain.size();
    }

    private void requireLegalRevision(long revision) {
        if (revision <= 0) {
            throw new IllegalArgumentException("非法 revision（须 > 0）: " + revision);
        }
        if (revision > currentRevision) {
            throw new IllegalArgumentException("非法 revision（超出当前游标 " + currentRevision + "）: " + revision);
        }
    }

    private void ensureRevisionSpace() {
        if (currentRevision == Long.MAX_VALUE) {
            throw new IllegalStateException("revision 空间耗尽（Long.MAX_VALUE）");
        }
    }

    private long nextRevision() {
        return ++currentRevision;
    }

    private TreeMap<Long, VersionedValue> chain(String key) {
        return chains.computeIfAbsent(key, k -> new TreeMap<>());
    }
}

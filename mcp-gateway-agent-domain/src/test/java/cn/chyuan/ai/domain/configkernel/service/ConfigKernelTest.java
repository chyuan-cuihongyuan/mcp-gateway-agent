package cn.chyuan.ai.domain.configkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置内核 BD1-BD7 单测（工单 0464-0470）：
 * MVCC 版本链/watch 历史补发/lease 级联/compact 保护/事务分支/快照等价/范围分页。
 */
class ConfigKernelTest {

    @Test
    void BD1_写入读取与版本链() {
        MvccStore store = new MvccStore();
        long r1 = store.put("app/name", "v1");
        long r2 = store.put("app/name", "v2");
        long r3 = store.put("app/owner", "ops");
        assertTrue(r1 < r2 && r2 < r3, "revision 全局单调");
        assertEquals("v2", store.get("app/name").value());
        assertEquals("v1", store.getAt("app/name", r1).value());
        assertEquals("v2", store.getAt("app/name", r3).value());
        assertEquals(2, store.versionChain("app/name").size());
    }

    @Test
    void BD1_删除墓碑与历史可见性() {
        MvccStore store = new MvccStore();
        store.put("k", "v");
        long delRev = store.delete("k");
        assertTrue(delRev > 0);
        assertFalse(store.get("k").present(), "最新为墓碑 → 视为不存在");
        assertTrue(store.versionChain("k").get(1).tombstone());
        assertEquals("v", store.getAt("k", delRev - 1).value(), "墓碑之下历史仍可读");
        assertEquals(0L, store.delete("missing"), "删不存在键幂等 no-op");
    }

    @Test
    void BD1_非法revision拒绝() {
        MvccStore store = new MvccStore();
        store.put("k", "v");
        assertThrows(IllegalArgumentException.class, () -> store.getAt("k", 0));
        assertThrows(IllegalArgumentException.class, () -> store.getAt("k", -1));
        assertThrows(IllegalArgumentException.class, () -> store.getAt("k", store.currentRevision() + 1));
    }

    @Test
    void BD2_watch前缀过滤与历史补发() {
        WatchStream watch = new WatchStream();
        watch.append(new WatchStream.Event(1, WatchStream.EventType.PUT, "app/a", "1"));
        watch.append(new WatchStream.Event(2, WatchStream.EventType.PUT, "db/a", "2"));
        watch.append(new WatchStream.Event(3, WatchStream.EventType.DELETE, "app/a", null));
        watch.append(new WatchStream.Event(4, WatchStream.EventType.PUT, "app/b", "4"));
        List<WatchStream.Event> replayed = watch.replayFrom(2, "app/");
        assertEquals(2, replayed.size(), "起点含该 revision");
        assertEquals(WatchStream.EventType.DELETE, replayed.get(0).type());
        assertEquals("app/b", replayed.get(1).key());
        List<WatchStream.Event> incremental = watch.after(3, "app/");
        assertEquals(1, incremental.size());
        assertEquals("app/b", incremental.get(0).key());
        assertEquals(4, watch.replayFrom(1, null).size(), "空前缀全量");
        assertThrows(IllegalArgumentException.class, () -> watch.replayFrom(0, "app/"));
    }

    @Test
    void BD3_租约保活与到期级联() {
        long[] now = {1_000L};
        LeaseManager leases = new LeaseManager(() -> now[0]);
        long lease = leases.grant(500L);
        leases.bindKey(lease, "app/session/a");
        leases.bindKey(lease, "app/session/b");
        assertEquals(1_500L, leases.keepAlive(lease), "续租重置到期");
        now[0] = 1_400L;
        assertTrue(leases.expiredLeases().isEmpty());
        now[0] = 1_501L;
        assertEquals(List.of(lease), leases.expiredLeases(), "过期检测");
        List<LeaseManager.RevokedLease> revoked = leases.revokeExpired();
        assertEquals(1, revoked.size());
        assertEquals(Set.of("app/session/a", "app/session/b"), Set.copyOf(revoked.get(0).cascadeDeleteKeys()));
        assertTrue(leases.expiredLeases().isEmpty(), "撤销后不再报告");
        assertThrows(IllegalArgumentException.class, () -> leases.keepAlive(lease), "撤销后续租拒绝");
        assertThrows(IllegalArgumentException.class, () -> leases.grant(0), "TTL 须为正");
    }

    @Test
    void BD4_压缩清理历史保留基线() {
        MvccStore store = new MvccStore();
        long r1 = store.put("k", "v1");
        long r2 = store.put("k", "v2");
        store.put("k", "v3");
        Compactor compactor = new Compactor(store);
        long removed = compactor.compactTo(r2);
        assertTrue(removed >= 1, "压缩清理 r2 之下历史");
        assertEquals("v2", store.getAt("k", r2).value(), "压缩点基线保留");
        assertThrows(IllegalArgumentException.class, () -> store.getAt("k", r1), "压缩点之下读取拒绝");
        assertEquals(0L, compactor.compactTo(r2 - 1), "游标只前进，重复压缩幂等");
        assertThrows(IllegalArgumentException.class, () -> compactor.compactTo(store.currentRevision() + 1));
    }

    @Test
    void BD4_墓碑基线整键清除与租约保护() {
        MvccStore store = new MvccStore();
        store.put("gone", "v");
        store.put("live", "keep");
        store.put("live", "keep2");
        store.delete("gone");
        Compactor compactor = new Compactor(store);
        compactor.protectKeys(Set.of("live"));
        compactor.compactTo(store.currentRevision());
        assertFalse(store.get("gone").present(), "墓碑基线整键清除后不可读");
        assertEquals("keep2", store.get("live").value(), "租约保护键完整保留");
        assertEquals(2, store.versionChain("live").size(), "保护键历史不清理");
    }

    @Test
    void BD5_事务比较与分支原子应用() {
        MvccStore store = new MvccStore();
        store.put("lock", "free");
        TxnCompare txn = new TxnCompare();
        TxnCompare.TxnResult ok = txn.apply(store,
                List.of(TxnCompare.Compare.value(TxnCompare.Op.EQ, "lock", "free")),
                List.of(TxnCompare.Action.put("lock", "held"), TxnCompare.Action.put("owner", "worker-1")),
                List.of(TxnCompare.Action.put("retry", "true")));
        assertTrue(ok.branchTaken());
        assertEquals(2, ok.actionRevisions().size());
        assertEquals("held", store.get("lock").value());
        assertEquals("worker-1", store.get("owner").value());
        TxnCompare.TxnResult miss = txn.apply(store,
                List.of(TxnCompare.Compare.value(TxnCompare.Op.EQ, "lock", "free")),
                List.of(TxnCompare.Action.put("owner", "worker-2")),
                List.of(TxnCompare.Action.put("retry", "true")));
        assertFalse(miss.branchTaken());
        assertEquals("true", store.get("retry").value());
        TxnCompare.TxnResult byVersion = txn.apply(store,
                List.of(TxnCompare.Compare.version(TxnCompare.Op.EQ, "lock",
                        store.versionChain("lock").size())),
                List.of(TxnCompare.Action.delete("owner")),
                List.of());
        assertTrue(byVersion.branchTaken());
        assertFalse(store.get("owner").present());
    }

    @Test
    void BD6_快照导出校验与恢复等价() {
        MvccStore store = new MvccStore();
        store.put("a", "1");
        store.put("b", "2");
        store.delete("a");
        long[] now = {100L};
        LeaseManager leases = new LeaseManager(() -> now[0]);
        long lease = leases.grant(1_000L);
        leases.bindKey(lease, "b");
        SnapshotCodec codec = new SnapshotCodec();
        SnapshotCodec.Snapshot snapshot = codec.export(store, leases);
        codec.requireValid(snapshot);
        SnapshotCodec.Restored restored = codec.restore(snapshot);
        assertEquals(store.currentRevision(), restored.store().currentRevision(), "revision 游标恢复");
        assertEquals(store.getAt("a", 1).value(), restored.store().getAt("a", 1).value(), "历史读取等价");
        assertFalse(restored.store().get("a").present());
        assertEquals("2", restored.store().get("b").value());
        assertEquals(1, restored.leases().snapshot().size(), "租约恢复");
        assertEquals(Set.of("b"), restored.leases().snapshot().get(0).boundKeys());
        SnapshotCodec.Snapshot tampered = new SnapshotCodec.Snapshot(snapshot.revision(),
                snapshot.entries(), snapshot.leases(), "deadbeefdeadbeef");
        assertThrows(IllegalStateException.class, () -> codec.requireValid(tampered), "校验和防篡改");
    }

    @Test
    void BD7_范围扫描分页与前缀计数() {
        MvccStore store = new MvccStore();
        for (String key : List.of("cfg/a", "cfg/b", "cfg/c", "db/x")) {
            store.put(key, "v-" + key);
        }
        store.put("cfg/dead", "x");
        store.delete("cfg/dead");
        RangeQuery query = new RangeQuery();
        RangeQuery.Page page1 = query.scan(store, "cfg/", "db/", 2);
        assertEquals(List.of("cfg/a", "cfg/b"), page1.items().stream().map(RangeQuery.Kv::key).toList());
        assertTrue(page1.hasMore());
        RangeQuery.Page page2 = query.scanAfter(store, page1.nextCursor(), "db/", 2);
        assertEquals(List.of("cfg/c"), page2.items().stream().map(RangeQuery.Kv::key).toList(),
                "墓碑键不出现在结果");
        assertFalse(page2.hasMore());
        assertEquals(3, query.prefixCount(store, "cfg/"), "前缀计数只计存活键");
        assertThrows(IllegalArgumentException.class, () -> query.scan(store, "cfg/", "db/", 0));
    }
}

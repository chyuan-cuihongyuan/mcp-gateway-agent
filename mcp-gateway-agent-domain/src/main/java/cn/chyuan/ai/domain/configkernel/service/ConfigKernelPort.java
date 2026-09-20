package cn.chyuan.ai.domain.configkernel.service;

import java.util.List;

/**
 * 配置内核端口（工单 0471 BD8）。
 * get/put/watch/lease 四能力面；内存假实现组合 MVCC/watch/lease 三内核，
 * 租约撤销级联删除绑定键。无外部服务依赖。
 */
public interface ConfigKernelPort {

    /** 写入配置键值，返回分配的 revision */
    long put(String key, String value);

    /** 读配置当前值（不存在或已删除返回 null） */
    String get(String key);

    /** 按 revision 订阅前缀匹配事件（历史补发） */
    List<WatchStream.Event> watch(String keyPrefix, long fromRevision);

    /** 注册 TTL 租约 */
    long grantLease(long ttlMillis);

    /** 键绑定租约 */
    void bindKeyToLease(long leaseId, String key);

    /** 撤销租约并级联删除绑定键，返回被删键 */
    List<String> revokeLease(long leaseId);

    /** 当前 revision 游标 */
    long currentRevision();

    /** 内存假实现：组合 MvccStore + WatchStream + LeaseManager */
    class InMemoryConfigKernel implements ConfigKernelPort {

        private final MvccStore store = new MvccStore();
        private final WatchStream watch = new WatchStream();
        private final LeaseManager leases = new LeaseManager(System::currentTimeMillis);

        @Override
        public synchronized long put(String key, String value) {
            long revision = store.put(key, value);
            watch.append(new WatchStream.Event(revision, WatchStream.EventType.PUT, key, value));
            return revision;
        }

        @Override
        public synchronized String get(String key) {
            MvccStore.HistoryResult result = store.get(key);
            return result.present() ? result.value() : null;
        }

        @Override
        public synchronized List<WatchStream.Event> watch(String keyPrefix, long fromRevision) {
            return watch.replayFrom(fromRevision, keyPrefix);
        }

        @Override
        public synchronized long grantLease(long ttlMillis) {
            return leases.grant(ttlMillis);
        }

        @Override
        public synchronized void bindKeyToLease(long leaseId, String key) {
            leases.bindKey(leaseId, key);
        }

        @Override
        public synchronized List<String> revokeLease(long leaseId) {
            List<String> bound = leases.revoke(leaseId);
            for (String key : bound) {
                store.delete(key);
            }
            return bound;
        }

        @Override
        public synchronized long currentRevision() {
            return store.currentRevision();
        }
    }
}

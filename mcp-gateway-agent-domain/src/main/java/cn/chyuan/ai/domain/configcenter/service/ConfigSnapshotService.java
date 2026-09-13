package cn.chyuan.ai.domain.configcenter.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配置快照版本服务（工单 0251 AG1，借鉴 Nacos 配置快照/Apollo 发布轨迹）—
 * 命名空间 + 配置键多版本管理：每次发布生成递增快照（不可变内容），同键单一 CURRENT 不变式，
 * 回滚 = 以历史版本内容重新发布为新快照（历史不被改写）。
 * 存储经 {@link SnapshotStore} 端口（infrastructure 落 config_snapshot 表）。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class ConfigSnapshotService {

    /** 同键单一 CURRENT 不变式违反（存储被旁路写坏时的防御性校验） */
    public static final String ERR_MULTIPLE_CURRENT = "同一配置键存在多个 CURRENT 快照";

    /** 状态：当前生效 */
    public static final String STATUS_CURRENT = "CURRENT";
    /** 状态：已被后续发布取代 */
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    private static final Set<String> LEGAL_STATUS = Set.of(STATUS_CURRENT, STATUS_SUPERSEDED);

    /** 配置快照值对象（content 对敏感项为密文 enc-v1:*，明文永不与密文混存判断由上层负责） */
    public record ConfigSnapshot(Long id, String namespace, String configKey, int version,
            String content, String contentMd5, boolean sensitive, String publisher,
            String note, String status) {

        public ConfigSnapshot {
            if (namespace == null || namespace.isBlank()) {
                throw new IllegalArgumentException("namespace 不能为空");
            }
            if (configKey == null || configKey.isBlank()) {
                throw new IllegalArgumentException("configKey 不能为空");
            }
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("content 不能为空");
            }
            if (status != null && !LEGAL_STATUS.contains(status)) {
                throw new IllegalArgumentException("非法状态: " + status);
            }
        }

        public boolean current() {
            return STATUS_CURRENT.equals(status);
        }
    }

    /** 快照持久化端口（infrastructure 经 MyBatis 落 config_snapshot 表） */
    public interface SnapshotStore {

        /** 同键当前最大版本号（无记录返回 0） */
        int maxVersionOf(String namespace, String configKey);

        void insert(ConfigSnapshot snapshot);

        void update(ConfigSnapshot snapshot);

        ConfigSnapshot find(String namespace, String configKey, int version);

        List<ConfigSnapshot> listByKey(String namespace, String configKey);

        List<ConfigSnapshot> listAll();

        List<ConfigSnapshot> listByNamespace(String namespace);
    }

    private final SnapshotStore store;

    /** 防御：并发发布时按键串行（同键单一 CURRENT 不变式） */
    private final Map<String, Object> publishLocks = new ConcurrentHashMap<>();

    public ConfigSnapshotService(SnapshotStore store) {
        this.store = store;
    }

    /** 发布：版本号 = 同键最大版本 + 1，前任 CURRENT 转 SUPERSEDED，新快照置 CURRENT */
    public ConfigSnapshot publish(String namespace, String configKey, String content,
            String contentMd5, boolean sensitive, String publisher, String note) {
        synchronized (publishLocks.computeIfAbsent(lockKey(namespace, configKey), k -> new Object())) {
            int next = store.maxVersionOf(namespace, configKey) + 1;
            store.listByKey(namespace, configKey).stream().filter(ConfigSnapshot::current).forEach(prev -> {
                store.update(new ConfigSnapshot(prev.id(), prev.namespace(), prev.configKey(), prev.version(),
                        prev.content(), prev.contentMd5(), prev.sensitive(), prev.publisher(),
                        prev.note(), STATUS_SUPERSEDED));
            });
            ConfigSnapshot snapshot = new ConfigSnapshot(null, namespace, configKey, next,
                    content, contentMd5, sensitive, publisher == null || publisher.isBlank() ? "unknown" : publisher,
                    note, STATUS_CURRENT);
            store.insert(snapshot);
            log.info("配置快照发布: ns={} key={} version={} sensitive={} publisher={}",
                    namespace, configKey, next, sensitive, snapshot.publisher());
            return snapshot;
        }
    }

    /** 回滚：目标历史版本内容重新发布为新版本（历史快照不可变），草稿语义不存在（全部可回滚目标） */
    public ConfigSnapshot rollback(String namespace, String configKey, int toVersion,
            String publisher, String note) {
        ConfigSnapshot target = require(namespace, configKey, toVersion);
        return publish(namespace, configKey, target.content(), target.contentMd5(),
                target.sensitive(), publisher, "回滚自 v" + toVersion + (note == null ? "" : "; " + note));
    }

    /** 当前生效快照（无则 null） */
    public ConfigSnapshot current(String namespace, String configKey) {
        return store.listByKey(namespace, configKey).stream()
                .filter(ConfigSnapshot::current)
                .findFirst()
                .orElse(null);
    }

    /** 指定版本快照 */
    public ConfigSnapshot get(String namespace, String configKey, int version) {
        return require(namespace, configKey, version);
    }

    /** 同键版本时间线（版本号倒序） */
    public List<ConfigSnapshot> timeline(String namespace, String configKey) {
        return store.listByKey(namespace, configKey).stream()
                .sorted(Comparator.comparingInt(ConfigSnapshot::version).reversed())
                .toList();
    }

    public List<ConfigSnapshot> listAll() {
        return store.listAll();
    }

    public List<ConfigSnapshot> listByNamespace(String namespace) {
        return store.listByNamespace(namespace);
    }

    private ConfigSnapshot require(String namespace, String configKey, int version) {
        ConfigSnapshot target = store.find(namespace, configKey, version);
        if (target == null) {
            throw new IllegalArgumentException("配置快照不存在: " + namespace + "/" + configKey + " v" + version);
        }
        return target;
    }

    private static String lockKey(String namespace, String configKey) {
        return namespace + "/" + configKey;
    }
}

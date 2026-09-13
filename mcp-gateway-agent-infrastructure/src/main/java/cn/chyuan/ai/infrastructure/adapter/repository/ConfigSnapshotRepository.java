package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.configcenter.service.ConfigSnapshotService;
import cn.chyuan.ai.domain.configcenter.service.ConfigSnapshotService.ConfigSnapshot;
import cn.chyuan.ai.infrastructure.dao.IConfigSnapshotDao;
import cn.chyuan.ai.infrastructure.dao.po.McpConfigSnapshotPO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 配置快照仓储实现（工单 0251 AG1）：实现 {@link ConfigSnapshotService.SnapshotStore} 端口，
 * 经 MyBatis 落 mcp_config_snapshot 表（双方言：mapper 仅用两库公共子集 SQL）。
 *
 * @author chyuan
 */
@Repository
public class ConfigSnapshotRepository implements ConfigSnapshotService.SnapshotStore {

    @Resource
    private IConfigSnapshotDao dao;

    @Override
    public int maxVersionOf(String namespace, String configKey) {
        Integer max = dao.queryMaxVersion(namespace, configKey);
        return max == null ? 0 : max;
    }

    @Override
    public void insert(ConfigSnapshot snapshot) {
        dao.insert(toPo(snapshot));
    }

    @Override
    public void update(ConfigSnapshot snapshot) {
        dao.update(toPo(snapshot));
    }

    @Override
    public ConfigSnapshot find(String namespace, String configKey, int version) {
        return toDomain(dao.query(namespace, configKey, version));
    }

    @Override
    public List<ConfigSnapshot> listByKey(String namespace, String configKey) {
        return dao.queryByKey(namespace, configKey).stream().map(ConfigSnapshotRepository::toDomain).toList();
    }

    @Override
    public List<ConfigSnapshot> listAll() {
        return dao.queryAll().stream().map(ConfigSnapshotRepository::toDomain).toList();
    }

    @Override
    public List<ConfigSnapshot> listByNamespace(String namespace) {
        return dao.queryByNamespace(namespace).stream().map(ConfigSnapshotRepository::toDomain).toList();
    }

    private static McpConfigSnapshotPO toPo(ConfigSnapshot s) {
        McpConfigSnapshotPO po = new McpConfigSnapshotPO();
        po.setId(s.id());
        po.setNamespace(s.namespace());
        po.setConfigKey(s.configKey());
        po.setVersion(s.version());
        po.setContent(s.content());
        po.setContentMd5(s.contentMd5());
        po.setSensitive(s.sensitive());
        po.setPublisher(s.publisher());
        po.setNote(s.note());
        po.setStatus(s.status());
        po.setUpdateTime(new java.util.Date());
        return po;
    }

    private static ConfigSnapshot toDomain(McpConfigSnapshotPO po) {
        if (po == null) {
            return null;
        }
        return new ConfigSnapshot(po.getId(), po.getNamespace(), po.getConfigKey(),
                po.getVersion() == null ? 0 : po.getVersion(), po.getContent(),
                po.getContentMd5(), Boolean.TRUE.equals(po.getSensitive()), po.getPublisher(),
                po.getNote(), po.getStatus());
    }
}

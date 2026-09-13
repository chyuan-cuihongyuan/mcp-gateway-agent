package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.modelcatalog.service.ModelCatalogService;
import cn.chyuan.ai.domain.modelcatalog.service.ModelCatalogService.CatalogStore;
import cn.chyuan.ai.domain.modelcatalog.service.ModelCatalogService.ModelEntry;
import cn.chyuan.ai.infrastructure.dao.IModelCatalogDao;
import cn.chyuan.ai.infrastructure.dao.po.McpModelCatalogPO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 模型目录仓储实现（工单 0281 AJ5）：实现 {@link CatalogStore} 端口，
 * 经 MyBatis 落 mcp_model_catalog 表（双方言公共子集 SQL）。
 *
 * @author chyuan
 */
@Repository
public class ModelCatalogRepository implements CatalogStore {

    @Resource
    private IModelCatalogDao dao;

    @Override
    public void insert(ModelEntry entry) {
        dao.insert(toPo(entry));
    }

    @Override
    public void update(ModelEntry entry) {
        dao.update(toPo(entry));
    }

    @Override
    public ModelEntry findByModel(String model) {
        return toDomain(dao.queryByModel(model));
    }

    @Override
    public List<ModelEntry> listAll() {
        return dao.queryAll().stream().map(ModelCatalogRepository::toDomain).toList();
    }

    static McpModelCatalogPO toPo(ModelEntry entry) {
        McpModelCatalogPO po = new McpModelCatalogPO();
        po.setId(entry.id());
        po.setModel(entry.model());
        po.setContextLimit(entry.contextLimit());
        po.setModalities(String.join(",", entry.modalities()));
        po.setPricingEntryId(entry.pricingEntryId());
        po.setStatus(entry.status());
        po.setNote(entry.note());
        po.setOperator(entry.operator());
        po.setUpdateTime(new java.util.Date());
        return po;
    }

    static ModelEntry toDomain(McpModelCatalogPO po) {
        if (po == null) {
            return null;
        }
        Set<String> modalities = po.getModalities() == null || po.getModalities().isBlank()
                ? Set.of("text")
                : Arrays.stream(po.getModalities().split(",")).map(String::trim).collect(Collectors.toSet());
        return new ModelEntry(po.getId(), po.getModel(),
                po.getContextLimit() == null ? 0 : po.getContextLimit(), modalities,
                po.getPricingEntryId(), po.getStatus(), po.getNote(), po.getOperator());
    }
}

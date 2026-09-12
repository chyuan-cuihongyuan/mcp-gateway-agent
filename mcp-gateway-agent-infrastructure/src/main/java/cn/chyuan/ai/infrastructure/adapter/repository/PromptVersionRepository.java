package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.promptresource.service.PromptVersionService;
import cn.chyuan.ai.domain.promptresource.service.PromptVersionService.PromptVersion;
import cn.chyuan.ai.infrastructure.dao.IPromptVersionDao;
import cn.chyuan.ai.infrastructure.dao.po.McpPromptVersionPO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 提示版本仓储实现（工单 0196 AA1）：实现 {@link PromptVersionService.VersionStore} 端口，
 * 经 MyBatis 落 mcp_prompt_version 表（双方言：mapper 仅用两库公共子集 SQL）。
 *
 * @author chyuan
 */
@Repository
public class PromptVersionRepository implements PromptVersionService.VersionStore {

    @Resource
    private IPromptVersionDao dao;

    @Override
    public int maxVersionOf(String promptName) {
        Integer max = dao.queryMaxVersion(promptName);
        return max == null ? 0 : max;
    }

    @Override
    public void insert(PromptVersion version) {
        dao.insert(toPo(version));
    }

    @Override
    public void update(PromptVersion version) {
        dao.update(toPo(version));
    }

    @Override
    public PromptVersion find(String promptName, int version) {
        return toDomain(dao.query(promptName, version));
    }

    @Override
    public List<PromptVersion> listByName(String promptName) {
        return dao.queryByName(promptName).stream().map(PromptVersionRepository::toDomain).toList();
    }

    @Override
    public List<PromptVersion> listAll() {
        return dao.queryAll().stream().map(PromptVersionRepository::toDomain).toList();
    }

    @Override
    public PromptVersion findByLabel(String promptName, String label) {
        return toDomain(dao.queryByLabel(promptName, label));
    }

    @Override
    public void clearLabels(String promptName) {
        dao.clearLabels(promptName);
    }

    private static McpPromptVersionPO toPo(PromptVersion v) {
        McpPromptVersionPO po = new McpPromptVersionPO();
        po.setId(v.id());
        po.setPromptName(v.promptName());
        po.setVersion(v.version());
        po.setTemplate(v.template());
        po.setStatus(v.status());
        po.setNote(v.note());
        po.setOperator(v.operator());
        po.setLabel(v.label());
        po.setUpdateTime(new java.util.Date());
        return po;
    }

    private static PromptVersion toDomain(McpPromptVersionPO po) {
        if (po == null) {
            return null;
        }
        return new PromptVersion(po.getId(), po.getPromptName(), po.getVersion() == null ? 0 : po.getVersion(),
                po.getTemplate(), po.getStatus(), po.getNote(), po.getOperator(), po.getLabel());
    }
}

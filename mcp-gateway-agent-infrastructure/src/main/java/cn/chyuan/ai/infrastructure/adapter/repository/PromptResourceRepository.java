package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.promptresource.adapter.repository.IPromptResourceRepository;
import cn.chyuan.ai.domain.promptresource.model.valobj.PromptVO;
import cn.chyuan.ai.domain.promptresource.model.valobj.ResourceVO;
import cn.chyuan.ai.infrastructure.dao.IPromptResourceDao;
import cn.chyuan.ai.infrastructure.dao.po.McpPromptPO;
import cn.chyuan.ai.infrastructure.dao.po.McpResourcePO;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 网关本地 Prompt/Resource 仓储实现（工单 0053）
 *
 * @author chyuan
 */
@Repository
public class PromptResourceRepository implements IPromptResourceRepository {

    @Resource
    private IPromptResourceDao dao;

    @Override
    public Long insertPrompt(PromptVO prompt) {
        McpPromptPO po = toPromptPo(prompt);
        dao.insertPrompt(po);
        return po.getId();
    }

    @Override
    public boolean updatePrompt(PromptVO prompt) {
        return dao.updatePrompt(toPromptPo(prompt)) > 0;
    }

    @Override
    public boolean deletePrompt(Long id) {
        return dao.deletePrompt(id) > 0;
    }

    @Override
    public PromptVO findPromptById(Long id) {
        return toPromptVo(dao.queryPromptById(id));
    }

    @Override
    public PromptVO findPrompt(String gatewayId, String name) {
        return toPromptVo(dao.queryPrompt(gatewayId, name));
    }

    @Override
    public List<PromptVO> findPrompts(String gatewayId) {
        return dao.queryPrompts(gatewayId).stream().map(this::toPromptVo).toList();
    }

    @Override
    public Long insertResource(ResourceVO resource) {
        McpResourcePO po = toResourcePo(resource);
        dao.insertResource(po);
        return po.getId();
    }

    @Override
    public boolean updateResource(ResourceVO resource) {
        return dao.updateResource(toResourcePo(resource)) > 0;
    }

    @Override
    public boolean deleteResource(Long id) {
        return dao.deleteResource(id) > 0;
    }

    @Override
    public ResourceVO findResourceById(Long id) {
        return toResourceVo(dao.queryResourceById(id));
    }

    @Override
    public ResourceVO findResource(String gatewayId, String uri) {
        return toResourceVo(dao.queryResource(gatewayId, uri));
    }

    @Override
    public List<ResourceVO> findResources(String gatewayId) {
        return dao.queryResources(gatewayId).stream().map(this::toResourceVo).toList();
    }

    private McpPromptPO toPromptPo(PromptVO vo) {
        return McpPromptPO.builder()
                .id(vo.getId()).gatewayId(vo.getGatewayId()).name(vo.getName())
                .description(vo.getDescription()).argumentsJson(vo.getArgumentsJson())
                .template(vo.getTemplate()).status(vo.getStatus() == null ? 1 : vo.getStatus())
                .build();
    }

    private PromptVO toPromptVo(McpPromptPO po) {
        if (po == null) {
            return null;
        }
        return PromptVO.builder()
                .id(po.getId()).gatewayId(po.getGatewayId()).name(po.getName())
                .description(po.getDescription()).argumentsJson(po.getArgumentsJson())
                .template(po.getTemplate()).status(po.getStatus())
                .createTime(po.getCreateTime()).updateTime(po.getUpdateTime())
                .build();
    }

    private McpResourcePO toResourcePo(ResourceVO vo) {
        return McpResourcePO.builder()
                .id(vo.getId()).gatewayId(vo.getGatewayId()).uri(vo.getUri()).name(vo.getName())
                .description(vo.getDescription()).mimeType(vo.getMimeType())
                .content(vo.getContent()).status(vo.getStatus() == null ? 1 : vo.getStatus())
                .build();
    }

    private ResourceVO toResourceVo(McpResourcePO po) {
        if (po == null) {
            return null;
        }
        return ResourceVO.builder()
                .id(po.getId()).gatewayId(po.getGatewayId()).uri(po.getUri()).name(po.getName())
                .description(po.getDescription()).mimeType(po.getMimeType())
                .content(po.getContent()).status(po.getStatus())
                .createTime(po.getCreateTime()).updateTime(po.getUpdateTime())
                .build();
    }
}

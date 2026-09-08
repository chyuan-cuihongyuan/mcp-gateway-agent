package cn.chyuan.ai.domain.promptresource.adapter.repository;

import cn.chyuan.ai.domain.promptresource.model.valobj.PromptVO;
import cn.chyuan.ai.domain.promptresource.model.valobj.ResourceVO;

import java.util.List;

/**
 * 网关本地 Prompt/Resource 仓储端口（工单 0053）
 *
 * @author chyuan
 */
public interface IPromptResourceRepository {

    Long insertPrompt(PromptVO prompt);

    boolean updatePrompt(PromptVO prompt);

    boolean deletePrompt(Long id);

    PromptVO findPromptById(Long id);

    PromptVO findPrompt(String gatewayId, String name);

    List<PromptVO> findPrompts(String gatewayId);

    Long insertResource(ResourceVO resource);

    boolean updateResource(ResourceVO resource);

    boolean deleteResource(Long id);

    ResourceVO findResourceById(Long id);

    ResourceVO findResource(String gatewayId, String uri);

    List<ResourceVO> findResources(String gatewayId);
}

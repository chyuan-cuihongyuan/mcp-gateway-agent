package cn.chyuan.ai.domain.llmchannel.adapter.repository;

import cn.chyuan.ai.domain.llmchannel.model.valobj.LlmChannelVO;

import java.util.List;

/**
 * LLM 渠道仓储端口（工单 0063）
 *
 * @author chyuan
 */
public interface ILlmChannelRepository {

    Long insert(LlmChannelVO channel);

    boolean update(LlmChannelVO channel);

    boolean deleteById(Long id);

    LlmChannelVO findById(Long id);

    LlmChannelVO findByName(String name);

    List<LlmChannelVO> findAll();

    /** 启用态渠道（路由用） */
    List<LlmChannelVO> findEnabled();
}

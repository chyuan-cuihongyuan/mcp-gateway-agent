package cn.chyuan.ai.api;

import cn.chyuan.ai.api.dto.LlmChannelRequestDTO;
import cn.chyuan.ai.api.dto.LlmChannelResponseDTO;

import java.util.List;

/**
 * admin LLM 渠道服务接口（工单 0063）
 *
 * @author chyuan
 */
public interface IAdminLlmChannelService {

    LlmChannelResponseDTO createChannel(LlmChannelRequestDTO requestDTO);

    LlmChannelResponseDTO updateChannel(Long id, LlmChannelRequestDTO requestDTO);

    void deleteChannel(Long id);

    LlmChannelResponseDTO getChannel(Long id);

    List<LlmChannelResponseDTO> listChannels();

    /** 渠道连通性测试（base_url/models 探测），返回耗时毫秒；失败抛 AppException */
    long testChannel(Long id);
}

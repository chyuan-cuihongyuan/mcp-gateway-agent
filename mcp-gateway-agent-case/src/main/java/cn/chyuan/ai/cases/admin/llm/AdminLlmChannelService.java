package cn.chyuan.ai.cases.admin.llm;

import cn.chyuan.ai.api.IAdminLlmChannelService;
import cn.chyuan.ai.api.dto.LlmChannelRequestDTO;
import cn.chyuan.ai.api.dto.LlmChannelResponseDTO;
import cn.chyuan.ai.domain.llmchannel.adapter.port.ILlmHttpPort;
import cn.chyuan.ai.domain.llmchannel.model.valobj.LlmChannelVO;
import cn.chyuan.ai.domain.llmchannel.service.LlmChannelAdminService;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import cn.chyuan.ai.types.exception.AppException;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * admin LLM 渠道编排（工单 0063：DTO 转换 + 连通性测试）
 *
 * @author chyuan
 */
@Service
public class AdminLlmChannelService implements IAdminLlmChannelService {

    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";

    @Resource
    private LlmChannelAdminService channelAdminService;

    @Resource
    private ILlmHttpPort llmHttpPort;

    @Override
    public LlmChannelResponseDTO createChannel(LlmChannelRequestDTO requestDTO) {
        return toDto(channelAdminService.create(toVo(null, requestDTO)));
    }

    @Override
    public LlmChannelResponseDTO updateChannel(Long id, LlmChannelRequestDTO requestDTO) {
        return toDto(channelAdminService.update(id, toVo(id, requestDTO)));
    }

    @Override
    public void deleteChannel(Long id) {
        channelAdminService.delete(id);
    }

    @Override
    public LlmChannelResponseDTO getChannel(Long id) {
        return toDto(channelAdminService.get(id));
    }

    @Override
    public List<LlmChannelResponseDTO> listChannels() {
        return channelAdminService.list().stream().map(this::toDto).toList();
    }

    @Override
    public long testChannel(Long id) {
        LlmChannelVO channel = channelAdminService.get(id);
        Map<String, String> headers = new HashMap<>();
        if (channel.getCredential() != null && !channel.getCredential().isBlank()) {
            headers.put("Authorization", "Bearer " + channel.getCredential());
        }
        long start = System.currentTimeMillis();
        try {
            String body = llmHttpPort.getJson(channel.getBaseUrl() + "/models", headers,
                    channel.getTimeoutMs() == null ? 10_000 : channel.getTimeoutMs());
            if (body == null || body.isBlank()) {
                throw new AppException(McpErrorCodes.TOOL_EXECUTION_FAILED, "渠道响应为空");
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(McpErrorCodes.TOOL_EXECUTION_FAILED, "渠道测试失败: " + e.getMessage());
        }
        // 余额探测（工单 0108）：配置了 probe 时顺带执行；失败不影响连通性结果
        try {
            channelAdminService.probeBalance(id, llmHttpPort);
        } catch (Exception ignored) {
            // 探测独立于连通性
        }
        return System.currentTimeMillis() - start;
    }

    private LlmChannelVO toVo(Long id, LlmChannelRequestDTO dto) {
        return LlmChannelVO.builder()
                .id(id).name(dto.getName()).baseUrl(dto.getBaseUrl()).credential(dto.getCredential())
                .models(dto.getModels()).modelMapping(dto.getModelMapping())
                .weight(dto.getWeight()).priority(dto.getPriority())
                .status(dto.getStatus()).timeoutMs(dto.getTimeoutMs())
                .numRetries(dto.getNumRetries()).retryBackoffMs(dto.getRetryBackoffMs())
                .retryOn(dto.getRetryOn())
                .balanceProbeUrl(dto.getBalanceProbeUrl()).balanceJsonPath(dto.getBalanceJsonPath())
                .balance(dto.getBalance())
                .build();
    }

    private LlmChannelResponseDTO toDto(LlmChannelVO vo) {
        return LlmChannelResponseDTO.builder()
                .id(vo.getId()).name(vo.getName()).baseUrl(vo.getBaseUrl())
                .credentialMasked(vo.getCredential() == null ? null : "****")
                .models(vo.getModels()).modelMapping(vo.getModelMapping())
                .weight(vo.getWeight()).priority(vo.getPriority()).status(vo.getStatus())
                .timeoutMs(vo.getTimeoutMs()).testTime(format(vo.getTestTime()))
                .numRetries(vo.getNumRetries()).retryBackoffMs(vo.getRetryBackoffMs())
                .retryOn(vo.getRetryOn())
                .balanceProbeUrl(vo.getBalanceProbeUrl()).balanceJsonPath(vo.getBalanceJsonPath())
                .balance(vo.getBalance()).balanceTime(format(vo.getBalanceTime()))
                .responseTimeMs(vo.getResponseTimeMs())
                .build();
    }

    private String format(Date value) {
        return value == null ? null : new SimpleDateFormat(DATE_PATTERN).format(value);
    }
}

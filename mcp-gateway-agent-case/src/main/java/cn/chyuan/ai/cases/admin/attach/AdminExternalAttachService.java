package cn.chyuan.ai.cases.admin.attach;

import cn.chyuan.ai.api.IAdminExternalAttachService;
import cn.chyuan.ai.api.dto.ExternalAttachResponseDTO;
import cn.chyuan.ai.api.dto.ExternalAttachTestResponseDTO;
import cn.chyuan.ai.api.dto.ExternalAttachUpsertRequestDTO;
import cn.chyuan.ai.domain.externalattach.adapter.port.IExternalMcpAttachPort;
import cn.chyuan.ai.domain.externalattach.model.valobj.ExternalAttachVO;
import cn.chyuan.ai.domain.externalattach.service.IExternalAttachAdminService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * admin 外部 MCP 挂接管理 case（工单 0021）
 *
 * <p>编排：domain CRUD（校验+审计）→ 端口探活/失效。变更后由端口侧
 * {@code evictAttach} 失效客户端并请求网关服务器目录刷新（保会话）。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class AdminExternalAttachService implements IAdminExternalAttachService {

    @Resource
    private IExternalAttachAdminService externalAttachAdminService;

    /** 端口可选注入（切片测试可缺省，探活/状态仅返回占位） */
    @Autowired(required = false)
    private IExternalMcpAttachPort externalMcpAttachPort;

    @Override
    public ExternalAttachResponseDTO createAttach(ExternalAttachUpsertRequestDTO requestDTO) {
        ExternalAttachVO vo = externalAttachAdminService.create(toVo(requestDTO));
        evict(vo.getId(), vo.getGatewayId());
        return toDto(vo, null);
    }

    @Override
    public ExternalAttachResponseDTO updateAttach(Long id, ExternalAttachUpsertRequestDTO requestDTO) {
        ExternalAttachVO vo = externalAttachAdminService.update(id, toVo(requestDTO));
        evict(vo.getId(), vo.getGatewayId());
        return toDto(vo, null);
    }

    @Override
    public void deleteAttach(Long id) {
        ExternalAttachVO existing = externalAttachAdminService.get(id);
        externalAttachAdminService.delete(id);
        evict(id, existing.getGatewayId());
    }

    @Override
    public ExternalAttachResponseDTO getAttach(Long id) {
        return toDto(externalAttachAdminService.get(id), null);
    }

    @Override
    public List<ExternalAttachResponseDTO> listByGateway(String gatewayId) {
        Map<Long, IExternalMcpAttachPort.AttachRuntimeStatus> runtime = runtimeStatusMap(gatewayId);
        return externalAttachAdminService.listByGateway(gatewayId).stream()
                .map(vo -> toDto(vo, runtime.get(vo.getId())))
                .toList();
    }

    @Override
    public ExternalAttachTestResponseDTO testAttach(ExternalAttachUpsertRequestDTO requestDTO) {
        ExternalAttachVO vo = toVo(requestDTO);
        if (externalMcpAttachPort == null) {
            return ExternalAttachTestResponseDTO.builder().connected(false).toolCount(0)
                    .error("挂接端口未装配").build();
        }
        IExternalMcpAttachPort.ConnectState state = externalMcpAttachPort.probeConnect(vo);
        return toTestDto(state);
    }

    @Override
    public ExternalAttachTestResponseDTO testAttachById(Long id) {
        ExternalAttachVO vo = externalAttachAdminService.get(id);
        if (externalMcpAttachPort == null) {
            return ExternalAttachTestResponseDTO.builder().connected(false).toolCount(0)
                    .error("挂接端口未装配").build();
        }
        // 复检走常驻客户端口径：先失效再探活，探活成功后下次访问重建
        IExternalMcpAttachPort.ConnectState state = externalMcpAttachPort.probeConnect(vo);
        if (state.connected()) {
            evict(vo.getId(), vo.getGatewayId());
        }
        return toTestDto(state);
    }

    private void evict(Long attachId, String gatewayId) {
        if (externalMcpAttachPort != null) {
            externalMcpAttachPort.evictAttach(attachId, gatewayId);
        }
    }

    private Map<Long, IExternalMcpAttachPort.AttachRuntimeStatus> runtimeStatusMap(String gatewayId) {
        if (externalMcpAttachPort == null) {
            return Map.of();
        }
        Map<Long, IExternalMcpAttachPort.AttachRuntimeStatus> map = new HashMap<>();
        externalMcpAttachPort.runtimeStatuses(gatewayId)
                .forEach(status -> map.put(status.attachId(), status));
        return map;
    }

    private ExternalAttachVO toVo(ExternalAttachUpsertRequestDTO dto) {
        return ExternalAttachVO.builder()
                .gatewayId(dto.getGatewayId())
                .attachName(dto.getAttachName())
                .transportType(dto.getTransportType())
                .endpoint(dto.getEndpoint())
                .apiKey(dto.getApiKey())
                .command(dto.getCommand())
                .args(dto.getArgs())
                .env(dto.getEnv())
                .requestTimeoutMs(dto.getRequestTimeoutMs())
                .status(dto.getStatus())
                .weight(dto.getWeight())
                .priority(dto.getPriority())
                .authType(dto.getAuthType())
                .authConfig(dto.getAuthConfig())
                .build();
    }

    private ExternalAttachResponseDTO toDto(ExternalAttachVO vo,
            IExternalMcpAttachPort.AttachRuntimeStatus runtime) {
        return ExternalAttachResponseDTO.builder()
                .id(vo.getId())
                .gatewayId(vo.getGatewayId())
                .attachName(vo.getAttachName())
                .transportType(vo.getTransportType())
                .endpoint(vo.getEndpoint())
                .apiKeyMasked(StringUtils.isBlank(vo.getApiKey()) ? null : "****")
                .command(vo.getCommand())
                .args(vo.getArgs())
                .env(vo.getEnv())
                .requestTimeoutMs(vo.getRequestTimeoutMs())
                .status(vo.getStatus())
                .weight(vo.getWeight())
                .priority(vo.getPriority())
                .testTime(vo.getTestTime())
                .responseTimeMs(vo.getResponseTimeMs())
                .cooldownUntil(vo.getCooldownUntil())
                .authType(vo.getAuthType())
                .authConfigMasked(vo.getAuthConfig() == null ? null : "****")
                .connectStatus(runtime != null ? runtime.connectStatus() : vo.getConnectStatus())
                .connectError(runtime != null ? runtime.connectError() : vo.getConnectError())
                .connectTime(runtime != null ? runtime.connectTime() : vo.getConnectTime())
                .toolCount(runtime != null ? runtime.toolCount() : 0)
                .createTime(vo.getCreateTime())
                .updateTime(vo.getUpdateTime())
                .build();
    }

    private ExternalAttachTestResponseDTO toTestDto(IExternalMcpAttachPort.ConnectState state) {
        return ExternalAttachTestResponseDTO.builder()
                .connected(state.connected())
                .toolCount(state.toolCount())
                .error(state.error())
                .build();
    }
}

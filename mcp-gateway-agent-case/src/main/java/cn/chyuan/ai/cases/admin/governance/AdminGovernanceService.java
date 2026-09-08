package cn.chyuan.ai.cases.admin.governance;

import cn.chyuan.ai.api.IAdminGovernanceService;
import cn.chyuan.ai.api.dto.AuditLogResponseDTO;
import cn.chyuan.ai.api.dto.CelRuleResponseDTO;
import cn.chyuan.ai.api.dto.CelRuleUpsertRequestDTO;
import cn.chyuan.ai.api.dto.LoginRequestDTO;
import cn.chyuan.ai.api.dto.LoginResponseDTO;
import cn.chyuan.ai.api.dto.VirtualKeyCreateRequestDTO;
import cn.chyuan.ai.api.dto.VirtualKeyResponseDTO;
import cn.chyuan.ai.api.dto.VirtualKeyUpdateRequestDTO;
import cn.chyuan.ai.api.response.ResponsePage;
import cn.chyuan.ai.domain.governance.adapter.repository.IAuditLogRepository;
import cn.chyuan.ai.domain.governance.model.entity.LoginCommandEntity;
import cn.chyuan.ai.domain.governance.model.entity.VirtualKeyCommandEntity;
import cn.chyuan.ai.domain.governance.model.valobj.CelRuleVO;
import cn.chyuan.ai.domain.governance.model.valobj.VirtualKeyVO;
import cn.chyuan.ai.domain.governance.service.IAdminAuthService;
import cn.chyuan.ai.domain.governance.service.IAuditService;
import cn.chyuan.ai.domain.governance.service.ICelRuleService;
import cn.chyuan.ai.domain.governance.service.IVirtualKeyService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * admin 治理面服务编排（工单 0017）
 *
 * <p>DTO ↔ 领域对象转换与分页封装；认证与密钥逻辑在 domain 层。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class AdminGovernanceService implements IAdminGovernanceService {

    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";

    @Resource
    private IVirtualKeyService virtualKeyService;

    @Resource
    private IAdminAuthService adminAuthService;

    @Resource
    private IAuditService auditService;

    @Resource
    private ICelRuleService celRuleService;

    @Override
    public LoginResponseDTO login(LoginRequestDTO requestDTO) {
        IAdminAuthService.LoginResult result = adminAuthService.login(
                LoginCommandEntity.builder()
                        .username(requestDTO.getUsername())
                        .password(requestDTO.getPassword())
                        .build());
        return LoginResponseDTO.builder()
                .token(result.token())
                .username(result.username())
                .role(result.role())
                .build();
    }

    @Override
    public VirtualKeyResponseDTO createVirtualKey(VirtualKeyCreateRequestDTO requestDTO) {
        VirtualKeyVO vo = virtualKeyService.create(toCommand(requestDTO));

        // 创建时一并授权
        if (requestDTO.getGrantGatewayIds() != null) {
            for (String gatewayId : requestDTO.getGrantGatewayIds()) {
                virtualKeyService.grant(vo.getId(), gatewayId);
            }
        }

        VirtualKeyResponseDTO response = toDto(vo);
        response.setGrants(virtualKeyService.getGrants(vo.getId()));
        return response;
    }

    @Override
    public VirtualKeyResponseDTO updateVirtualKey(Long id, VirtualKeyUpdateRequestDTO requestDTO) {
        VirtualKeyCommandEntity command = VirtualKeyCommandEntity.builder()
                .id(id)
                .keyName(requestDTO.getKeyName())
                .ownerUserId(requestDTO.getOwnerUserId())
                .tenantId(requestDTO.getTenantId())
                .expiresAt(parseDate(requestDTO.getExpiresAt()))
                .ipAllowList(requestDTO.getIpAllowList())
                .rpmLimit(requestDTO.getRpmLimit())
                .dailyRequestLimit(requestDTO.getDailyRequestLimit())
                .dailyToolCallLimit(requestDTO.getDailyToolCallLimit())
                .tpmLimit(requestDTO.getTpmLimit())
                .dailyCostLimit(requestDTO.getDailyCostLimit())
                .build();
        virtualKeyService.update(command);

        if (requestDTO.getGrantGatewayIds() != null) {
            for (String gatewayId : requestDTO.getGrantGatewayIds()) {
                virtualKeyService.grant(id, gatewayId);
            }
        }
        if (requestDTO.getRevokeGatewayIds() != null) {
            for (String gatewayId : requestDTO.getRevokeGatewayIds()) {
                virtualKeyService.revokeGrant(id, gatewayId);
            }
        }

        return getVirtualKey(id);
    }

    @Override
    public void revokeVirtualKey(Long id) {
        virtualKeyService.revoke(id);
    }

    @Override
    public void grantGateway(Long id, String gatewayId) {
        virtualKeyService.grant(id, gatewayId);
    }

    @Override
    public void revokeGrantGateway(Long id, String gatewayId) {
        virtualKeyService.revokeGrant(id, gatewayId);
    }

    @Override
    public VirtualKeyResponseDTO getVirtualKey(Long id) {
        VirtualKeyVO vo = virtualKeyService.getById(id);
        if (vo == null) {
            return null;
        }
        VirtualKeyResponseDTO dto = toDto(vo);
        dto.setGrants(virtualKeyService.getGrants(id));
        return dto;
    }

    @Override
    public ResponsePage<List<VirtualKeyResponseDTO>> pageVirtualKeys(String keyword, int page, int size) {
        List<VirtualKeyResponseDTO> list = virtualKeyService.page(keyword, page, size).stream()
                .map(this::toDto)
                .toList();
        long total = virtualKeyService.count(keyword);
        return ResponsePage.success(list, total);
    }

    @Override
    public ResponsePage<List<AuditLogResponseDTO>> pageAuditLogs(String resourceType, String resourceId, int page, int size) {
        List<AuditLogResponseDTO> list = auditService.page(resourceType, resourceId, page, size).stream()
                .map(log -> AuditLogResponseDTO.builder()
                        .id(log.getId())
                        .actor(log.getActor())
                        .action(log.getAction())
                        .resourceType(log.getResourceType())
                        .resourceId(log.getResourceId())
                        .beforeJson(log.getBeforeJson())
                        .afterJson(log.getAfterJson())
                        .createdAt(formatDate(log.getCreatedAt()))
                        .build())
                .toList();
        long total = auditService.count(resourceType, resourceId);
        return ResponsePage.success(list, total);
    }

    @Override
    public ResponsePage<List<CelRuleResponseDTO>> pageCelRules(String keyword, int page, int size) {
        List<CelRuleResponseDTO> list = celRuleService.page(keyword, page, size).stream()
                .map(this::toCelRuleDto)
                .toList();
        return ResponsePage.success(list, celRuleService.count(keyword));
    }

    @Override
    public CelRuleResponseDTO createCelRule(CelRuleUpsertRequestDTO requestDTO) {
        return toCelRuleDto(celRuleService.create(toCelRuleCommand(null, requestDTO)));
    }

    @Override
    public CelRuleResponseDTO updateCelRule(Long id, CelRuleUpsertRequestDTO requestDTO) {
        return toCelRuleDto(celRuleService.update(toCelRuleCommand(id, requestDTO)));
    }

    @Override
    public void deleteCelRule(Long id) {
        celRuleService.delete(id);
    }

    @Override
    public CelRuleResponseDTO getCelRule(Long id) {
        return toCelRuleDto(celRuleService.getById(id));
    }

    @Override
    public String validateCelExpression(String expression) {
        return celRuleService.validateExpression(expression);
    }

    private CelRuleVO toCelRuleCommand(Long id, CelRuleUpsertRequestDTO dto) {
        return CelRuleVO.builder()
                .id(id)
                .ruleName(dto.getRuleName())
                .expression(dto.getExpression())
                .scopeType(dto.getScopeType())
                .gatewayId(dto.getGatewayId())
                .virtualKeyId(dto.getVirtualKeyId())
                .status(dto.getStatus() == null || dto.getStatus().isBlank() ? "ACTIVE" : dto.getStatus())
                .build();
    }

    private CelRuleResponseDTO toCelRuleDto(CelRuleVO vo) {
        if (vo == null) {
            return null;
        }
        return CelRuleResponseDTO.builder()
                .id(vo.getId())
                .ruleName(vo.getRuleName())
                .expression(vo.getExpression())
                .scopeType(vo.getScopeType())
                .gatewayId(vo.getGatewayId())
                .virtualKeyId(vo.getVirtualKeyId())
                .status(vo.getStatus())
                .createdAt(formatDate(vo.getCreatedAt()))
                .updatedAt(formatDate(vo.getUpdatedAt()))
                .build();
    }

    private VirtualKeyCommandEntity toCommand(VirtualKeyCreateRequestDTO dto) {
        return VirtualKeyCommandEntity.builder()
                .keyName(dto.getKeyName())
                .ownerUserId(dto.getOwnerUserId())
                .tenantId(dto.getTenantId())
                .expiresAt(parseDate(dto.getExpiresAt()))
                .ipAllowList(dto.getIpAllowList())
                .rpmLimit(dto.getRpmLimit())
                .dailyRequestLimit(dto.getDailyRequestLimit())
                .dailyToolCallLimit(dto.getDailyToolCallLimit())
                .tpmLimit(dto.getTpmLimit())
                .dailyCostLimit(dto.getDailyCostLimit())
                .build();
    }

    private VirtualKeyResponseDTO toDto(VirtualKeyVO vo) {
        return VirtualKeyResponseDTO.builder()
                .id(vo.getId())
                .maskedKey(vo.getMaskedKey())
                .plaintextOnce(vo.getPlaintextOnce())
                .keyName(vo.getKeyName())
                .ownerUserId(vo.getOwnerUserId())
                .tenantId(vo.getTenantId())
                .status(vo.getStatus())
                .derivedStatus(vo.getDerivedStatus())
                .expiresAt(formatDate(vo.getExpiresAt()))
                .lastActiveAt(formatDate(vo.getLastActiveAt()))
                .ipAllowList(vo.getIpAllowList())
                .rpmLimit(vo.getRpmLimit())
                .dailyRequestLimit(vo.getDailyRequestLimit())
                .dailyToolCallLimit(vo.getDailyToolCallLimit())
                .tpmLimit(vo.getTpmLimit())
                .dailyCostLimit(vo.getDailyCostLimit())
                .createdAt(formatDate(vo.getCreatedAt()))
                .build();
    }

    private Date parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new SimpleDateFormat(DATE_PATTERN).parse(value);
        } catch (ParseException e) {
            throw new IllegalArgumentException("时间格式非法（期望 " + DATE_PATTERN + "）：" + value);
        }
    }

    private String formatDate(Date value) {
        return value == null ? null : new SimpleDateFormat(DATE_PATTERN).format(value);
    }
}

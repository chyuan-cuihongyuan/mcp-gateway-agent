package cn.chyuan.ai.cases.admin.governance;

import cn.chyuan.ai.api.IAdminGuardrailService;
import cn.chyuan.ai.api.dto.GuardrailDTO;
import cn.chyuan.ai.domain.governance.adapter.repository.IGuardrailRepository;
import cn.chyuan.ai.domain.governance.model.valobj.GuardrailVO;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import cn.chyuan.ai.types.exception.AppException;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 治理护栏管理用例（工单 0091）：CRUD 校验 + 快照失效 + 审计（SECURITY）
 *
 * @author chyuan
 */
@Service
public class AdminGuardrailService implements IAdminGuardrailService {

    private static final Set<String> TYPES = Set.of(
            GuardrailVO.TYPE_PII_MASK, GuardrailVO.TYPE_KEYWORD_BLOCK, GuardrailVO.TYPE_REGEX_BLOCK,
            GuardrailVO.TYPE_RESPONSE_FILTER, GuardrailVO.TYPE_RESPONSE_MASK);
    private static final Set<String> MODES = Set.of(
            GuardrailVO.MODE_PRE_CALL, GuardrailVO.MODE_POST_CALL, GuardrailVO.MODE_LOGGING_ONLY);
    private static final Set<String> TRAFFICS = Set.of(
            GuardrailVO.TRAFFIC_MCP, GuardrailVO.TRAFFIC_LLM, GuardrailVO.TRAFFIC_ALL);

    @Resource
    private IGuardrailRepository repository;

    @Resource
    private cn.chyuan.ai.domain.governance.service.GuardrailChain guardrailChain;

    @Resource
    private cn.chyuan.ai.domain.governance.service.IAuditService auditService;

    @Override
    public List<GuardrailDTO> listGuardrails() {
        return repository.findAll().stream().map(this::toDto).toList();
    }

    @Override
    public GuardrailDTO getGuardrail(Long id) {
        return toDto(require(id));
    }

    @Override
    public GuardrailDTO createGuardrail(GuardrailDTO dto) {
        validate(dto, true);
        if (repository.findByName(dto.getName()) != null) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "护栏名已存在: " + dto.getName());
        }
        GuardrailVO vo = toVo(dto);
        Long id = repository.insert(vo);
        afterWrite("CREATE_GUARDRAIL", dto.getName());
        vo.setId(id);
        return toDto(vo);
    }

    @Override
    public GuardrailDTO updateGuardrail(Long id, GuardrailDTO dto) {
        GuardrailVO before = require(id);
        dto.setId(id);
        validate(dto, false);
        dto.setName(before.getName());
        repository.update(toVo(dto));
        afterWrite("UPDATE_GUARDRAIL", dto.getName());
        return toDto(repository.findById(id));
    }

    @Override
    public void deleteGuardrail(Long id) {
        GuardrailVO before = require(id);
        repository.deleteById(id);
        afterWrite("DELETE_GUARDRAIL", before.getName());
    }

    private GuardrailVO require(Long id) {
        GuardrailVO vo = repository.findById(id);
        if (vo == null) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "护栏不存在: " + id);
        }
        return vo;
    }

    private void validate(GuardrailDTO dto, boolean forCreate) {
        if (forCreate && StringUtils.isBlank(dto.getName())) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "护栏名不能为空");
        }
        if (!TYPES.contains(dto.getType())) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "非法护栏类型: " + dto.getType());
        }
        if (!MODES.contains(dto.getMode())) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "非法护栏模式: " + dto.getMode());
        }
        if (dto.getTrafficMask() == null || !TRAFFICS.contains(dto.getTrafficMask())) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "非法作用流量面: " + dto.getTrafficMask());
        }
        if (StringUtils.isNotBlank(dto.getConfig())) {
            try {
                JSON.parseObject(dto.getConfig());
            } catch (Exception e) {
                throw new AppException(McpErrorCodes.INVALID_PARAMS, "护栏配置非合法 JSON");
            }
            validatePatternsForType(dto);
        }
    }

    /** REGEX_BLOCK 的 patterns 须可编译；KEYWORD 系须有 keywords 数组 */
    private void validatePatternsForType(GuardrailDTO dto) {
        if (GuardrailVO.TYPE_REGEX_BLOCK.equals(dto.getType())) {
            JSONArray patterns;
            try {
                patterns = JSON.parseObject(dto.getConfig()).getJSONArray("patterns");
            } catch (Exception e) {
                return;
            }
            if (patterns == null || patterns.isEmpty()) {
                throw new AppException(McpErrorCodes.INVALID_PARAMS, "REGEX_BLOCK 需要非空 patterns 数组");
            }
            for (Object pattern : patterns) {
                try {
                    Pattern.compile(String.valueOf(pattern));
                } catch (PatternSyntaxException e) {
                    throw new AppException(McpErrorCodes.INVALID_PARAMS,
                            "正则非法: " + (pattern == null ? "null" : pattern) + "（" + e.getDescription() + "）");
                }
            }
        }
    }

    /** 写后：失效本实例快照 + 审计（SECURITY 型） */
    private void afterWrite(String action, String name) {
        guardrailChain.invalidateSnapshot();
        auditService.record(cn.chyuan.ai.domain.governance.model.entity.AuditCommandEntity.builder()
                .actor("admin")
                .action(action)
                .resourceType("GUARDRAIL")
                .resourceId(name)
                .build());
    }

    private GuardrailVO toVo(GuardrailDTO dto) {
        return GuardrailVO.builder()
                .id(dto.getId())
                .name(dto.getName())
                .type(dto.getType())
                .mode(dto.getMode())
                .config(dto.getConfig())
                .trafficMask(dto.getTrafficMask())
                .priority(dto.getPriority() == null ? 100 : dto.getPriority())
                .enabled(dto.getEnabled() == null ? 1 : dto.getEnabled())
                .build();
    }

    private GuardrailDTO toDto(GuardrailVO vo) {
        GuardrailDTO dto = new GuardrailDTO();
        dto.setId(vo.getId());
        dto.setName(vo.getName());
        dto.setType(vo.getType());
        dto.setMode(vo.getMode());
        dto.setConfig(vo.getConfig());
        dto.setTrafficMask(vo.getTrafficMask());
        dto.setPriority(vo.getPriority());
        dto.setEnabled(vo.getEnabled());
        dto.setUpdateTime(vo.getUpdateTime() == null ? null
                : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(vo.getUpdateTime()));
        return dto;
    }
}

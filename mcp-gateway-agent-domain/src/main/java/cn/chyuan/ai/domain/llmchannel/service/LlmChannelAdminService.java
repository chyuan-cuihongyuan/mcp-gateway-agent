package cn.chyuan.ai.domain.llmchannel.service;

import cn.chyuan.ai.domain.governance.adapter.repository.IAuditLogRepository;
import cn.chyuan.ai.domain.governance.model.entity.AuditCommandEntity;
import cn.chyuan.ai.domain.governance.service.ConfigHotReloadService;
import cn.chyuan.ai.domain.governance.service.IAuditService;
import cn.chyuan.ai.domain.llmchannel.adapter.repository.ILlmChannelRepository;
import cn.chyuan.ai.domain.llmchannel.model.valobj.LlmChannelVO;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * LLM 渠道管理服务（工单 0063）
 *
 * <p>CRUD 校验（base URL 形态、models 非空、modelMapping JSON）+ 审计（credential 恒脱敏）。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class LlmChannelAdminService {

    @Resource
    private ILlmChannelRepository repository;

    @Resource
    private IAuditService auditService;

    @Resource
    private ConfigHotReloadService configHotReloadService;

    public LlmChannelVO create(LlmChannelVO channel) {
        validate(channel, true);
        normalize(channel);
        Long id = repository.insert(channel);
        channel.setId(id);
        audit("CREATE_LLM_CHANNEL", channel.getName(), channel);
        configHotReloadService.notifyChange(ConfigHotReloadService.TYPE_LLM_CHANNEL, channel.getName());
        return channel;
    }

    public LlmChannelVO update(Long id, LlmChannelVO channel) {
        LlmChannelVO existing = requireChannel(id);
        channel.setId(id);
        // 渠道名不可改（调度与审计引用）
        channel.setName(existing.getName());
        if (StringUtils.isBlank(channel.getCredential())) {
            channel.setCredential(existing.getCredential());
        }
        validate(channel, false);
        normalize(channel);
        repository.update(channel);
        audit("UPDATE_LLM_CHANNEL", channel.getName(), channel);
        configHotReloadService.notifyChange(ConfigHotReloadService.TYPE_LLM_CHANNEL, channel.getName());
        return repository.findById(id);
    }

    public void delete(Long id) {
        LlmChannelVO existing = requireChannel(id);
        repository.deleteById(id);
        audit("DELETE_LLM_CHANNEL", existing.getName(), null);
        configHotReloadService.notifyChange(ConfigHotReloadService.TYPE_LLM_CHANNEL, existing.getName());
    }

    public LlmChannelVO get(Long id) {
        return requireChannel(id);
    }

    public List<LlmChannelVO> list() {
        return repository.findAll();
    }

    private void validate(LlmChannelVO channel, boolean forCreate) {
        if (forCreate && (StringUtils.isBlank(channel.getName()) || StringUtils.isBlank(channel.getBaseUrl()))) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "name/baseUrl 不能为空");
        }
        if (StringUtils.isNotBlank(channel.getBaseUrl())
                && !(channel.getBaseUrl().startsWith("http://") || channel.getBaseUrl().startsWith("https://"))) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "baseUrl 必须是 http(s) URL");
        }
        if (StringUtils.isBlank(channel.getModels())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "models 不能为空（逗号分隔模型名）");
        }
        if (StringUtils.isNotBlank(channel.getModelMapping())) {
            try {
                JSON.parseObject(channel.getModelMapping());
            } catch (Exception e) {
                throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                        "modelMapping 必须是 JSON 对象（客户端名→上游名）");
            }
        }
        if (channel.getWeight() != null && channel.getWeight() < 1) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "weight 须 >=1");
        }
        if (channel.getPriority() != null && channel.getPriority() < 0) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "priority 须 >=0");
        }
        if (channel.getStatus() != null && channel.getStatus() != 0 && channel.getStatus() != 1) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "status 仅允许 0/1（2 系统置位）");
        }
        if (channel.getTimeoutMs() != null
                && (channel.getTimeoutMs() < 1000 || channel.getTimeoutMs() > 300000)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "timeoutMs 须在 1000-300000 毫秒");
        }
    }

    private void normalize(LlmChannelVO channel) {
        if (channel.getWeight() == null) {
            channel.setWeight(1);
        }
        if (channel.getPriority() == null) {
            channel.setPriority(0);
        }
        if (channel.getStatus() == null) {
            channel.setStatus(LlmChannelVO.STATUS_ENABLED);
        }
        if (channel.getTimeoutMs() == null) {
            channel.setTimeoutMs(60_000);
        }
    }

    private LlmChannelVO requireChannel(Long id) {
        LlmChannelVO channel = repository.findById(id);
        if (channel == null) {
            throw new AppException(ResponseCode.METHOD_NOT_FOUND.getCode(), "LLM 渠道不存在: " + id);
        }
        return channel;
    }

    private void audit(String action, String resourceId, LlmChannelVO channel) {
        auditService.record(AuditCommandEntity.builder()
                .actor("admin").action(action).resourceType("LLM_CHANNEL").resourceId(resourceId)
                .afterJson(channel == null ? null : JSON.toJSONString(masked(channel)))
                .build());
    }

    /** credential 恒脱敏 */
    private static LlmChannelVO masked(LlmChannelVO channel) {
        LlmChannelVO copy = new LlmChannelVO();
        copy.setId(channel.getId());
        copy.setName(channel.getName());
        copy.setBaseUrl(channel.getBaseUrl());
        copy.setCredential(channel.getCredential() == null ? null : "****");
        copy.setModels(channel.getModels());
        copy.setModelMapping(channel.getModelMapping());
        copy.setWeight(channel.getWeight());
        copy.setPriority(channel.getPriority());
        copy.setStatus(channel.getStatus());
        return copy;
    }
}

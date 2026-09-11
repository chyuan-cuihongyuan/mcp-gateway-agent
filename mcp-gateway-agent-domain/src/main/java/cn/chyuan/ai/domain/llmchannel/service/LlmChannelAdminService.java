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

    /** HTTP 端口（工单 0108 余额探测；切片测试上下文可缺省） */
    @Resource
    private org.springframework.beans.factory.ObjectProvider<cn.chyuan.ai.domain.llmchannel.adapter.port.ILlmHttpPort> llmHttpPortProvider;

    public LlmChannelVO create(LlmChannelVO channel) {
        validate(channel, true);
        assertFallbackAcyclic(channel.getId(), channel.getFallbackChannelId());
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
        assertFallbackAcyclic(id, channel.getFallbackChannelId());
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

    /** 余额探测（工单 0108）：GET probe URL + JSON 路径提取；失败返回 null（不影响连通性测试） */
    public String probeBalance(Long id, cn.chyuan.ai.domain.llmchannel.adapter.port.ILlmHttpPort httpPort) {
        LlmChannelVO channel = requireChannel(id);
        if (channel.getBalanceProbeUrl() == null || channel.getBalanceProbeUrl().isBlank()
                || channel.getBalanceJsonPath() == null || channel.getBalanceJsonPath().isBlank()) {
            return null;
        }
        try {
            cn.chyuan.ai.domain.llmchannel.adapter.port.ILlmHttpPort port =
                    llmHttpPortProvider == null ? null : llmHttpPortProvider.getIfAvailable();
            if (port == null) {
                return null;
            }
            String json = port.getJson(channel.getBalanceProbeUrl(), java.util.Map.of(), 5000);
            String value = extractJsonPath(JSON.parseObject(json), channel.getBalanceJsonPath().split("\\."));
            if (value != null) {
                repository.updateBalance(id, value, new java.util.Date());
            }
            return value;
        } catch (Exception e) {
            return null;
        }
    }

    /** 点路径提取（data.total_amount）；缺失返回 null */
    static String extractJsonPath(com.alibaba.fastjson.JSONObject body, String[] path) {
        com.alibaba.fastjson.JSONObject current = body;
        for (int i = 0; i < path.length - 1; i++) {
            if (current == null) {
                return null;
            }
            current = current.getJSONObject(path[i]);
        }
        if (current == null || path.length == 0) {
            return null;
        }
        Object leaf = current.get(path[path.length - 1]);
        return leaf == null ? null : String.valueOf(leaf);
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
        // 渠道预算（工单 0156）：请求体上限空=不限（沿用全局默认），配置须为正整数
        if (channel.getMaxBodyBytes() != null && channel.getMaxBodyBytes() <= 0) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "maxBodyBytes 须为正整数（空=不限）");
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

    /**
     * fallback 防环校验（工单 0155，A→B→A 拒绝保存）：目标渠道须存在；
     * 以全量渠道既有 fallback 边为底图、按新边覆盖 self 后走 FallbackChainPolicy 纯函数判定。
     */
    private void assertFallbackAcyclic(Long selfId, Long fallbackChannelId) {
        if (fallbackChannelId == null) {
            return;
        }
        if (repository.findById(fallbackChannelId) == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "fallback 渠道不存在: " + fallbackChannelId);
        }
        java.util.Map<Long, Long> edges = new java.util.HashMap<>();
        for (LlmChannelVO ch : repository.findAll()) {
            if (ch.getFallbackChannelId() != null) {
                edges.put(ch.getId(), ch.getFallbackChannelId());
            }
        }
        if (selfId != null) {
            edges.put(selfId, fallbackChannelId);
        }
        if (selfId != null && FallbackChainPolicy.createsCycle(edges, selfId, fallbackChannelId)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "fallback 链形成环（self→" + fallbackChannelId + "），拒绝保存");
        }
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
        copy.setFallbackChannelId(channel.getFallbackChannelId());
        copy.setMaxBodyBytes(channel.getMaxBodyBytes());
        return copy;
    }
}

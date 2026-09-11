package cn.chyuan.ai.domain.llmchannel.service;

import cn.chyuan.ai.domain.governance.model.entity.AuditCommandEntity;
import cn.chyuan.ai.domain.governance.service.IAuditService;
import cn.chyuan.ai.domain.llmchannel.adapter.repository.IRoutingRuleRepository;
import cn.chyuan.ai.domain.llmchannel.model.valobj.RoutingRuleVO;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * tag 路由规则管理服务（工单 0160）
 *
 * <p>CRUD 校验：字段非空、优先级 >=0、规则名唯一、（tagKey,tagValue,priority）三元组
 * 优先级冲突拒绝；变更写审计。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class RoutingRuleAdminService {

    private static final String RESOURCE_TYPE = "ROUTING_RULE";

    @Resource
    private IRoutingRuleRepository repository;

    @Resource
    private IAuditService auditService;

    public RoutingRuleVO create(RoutingRuleVO rule) {
        validate(rule, true);
        normalize(rule);
        Long id = repository.insert(rule);
        rule.setId(id);
        audit("CREATE_ROUTING_RULE", rule.getRuleName(), rule);
        return rule;
    }

    public RoutingRuleVO update(Long id, RoutingRuleVO rule) {
        RoutingRuleVO existing = requireRule(id);
        rule.setId(id);
        if (StringUtils.isBlank(rule.getStatus())) {
            rule.setStatus(existing.getStatus());
        }
        validate(rule, false);
        normalize(rule);
        repository.update(rule);
        audit("UPDATE_ROUTING_RULE", rule.getRuleName(), rule);
        return repository.findById(id);
    }

    public void delete(Long id) {
        RoutingRuleVO existing = requireRule(id);
        repository.deleteById(id);
        audit("DELETE_ROUTING_RULE", existing.getRuleName(), null);
    }

    public RoutingRuleVO get(Long id) {
        return requireRule(id);
    }

    public List<RoutingRuleVO> list() {
        return repository.findAll();
    }

    private void validate(RoutingRuleVO rule, boolean forCreate) {
        if (StringUtils.isBlank(rule.getRuleName())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "ruleName 不能为空");
        }
        if (StringUtils.isBlank(rule.getTagKey()) || StringUtils.isBlank(rule.getTagValue())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "tagKey/tagValue 不能为空");
        }
        if (StringUtils.isBlank(rule.getChannelGroupId())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "channelGroupId 不能为空");
        }
        if (rule.getTagKey().contains("=") || rule.getTagValue().contains("=")) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "tagKey/tagValue 不可包含 '='");
        }
        if (rule.getPriority() != null && rule.getPriority() < 0) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "priority 须 >=0");
        }
        RoutingRuleVO byName = repository.findByName(rule.getRuleName());
        if (byName != null && (forCreate || !byName.getId().equals(rule.getId()))) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "规则名已存在: " + rule.getRuleName());
        }
        // 优先级冲突（工单 0160）：同一 (tagKey,tagValue) 上同优先级多规则会让择序不确定，拒绝保存
        for (RoutingRuleVO other : repository.findAll()) {
            boolean sameTag = other.getTagKey() != null && other.getTagKey().equalsIgnoreCase(rule.getTagKey())
                    && other.getTagValue() != null && other.getTagValue().equalsIgnoreCase(rule.getTagValue());
            boolean samePriority = (other.getPriority() == null ? 0 : other.getPriority())
                    == (rule.getPriority() == null ? 0 : rule.getPriority());
            boolean self = !forCreate && other.getId().equals(rule.getId());
            if (sameTag && samePriority && !self) {
                throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                        "优先级冲突：规则 " + other.getRuleName() + " 已在相同标签对上使用优先级 "
                                + (other.getPriority() == null ? 0 : other.getPriority()));
            }
        }
    }

    private void normalize(RoutingRuleVO rule) {
        if (rule.getPriority() == null) {
            rule.setPriority(0);
        }
        if (StringUtils.isBlank(rule.getStatus())) {
            rule.setStatus(RoutingRuleVO.STATUS_ACTIVE);
        }
        rule.setTagKey(rule.getTagKey().trim());
        rule.setTagValue(rule.getTagValue().trim());
        rule.setChannelGroupId(rule.getChannelGroupId().trim());
    }

    private RoutingRuleVO requireRule(Long id) {
        RoutingRuleVO rule = repository.findById(id);
        if (rule == null) {
            throw new AppException(ResponseCode.METHOD_NOT_FOUND.getCode(), "路由规则不存在: " + id);
        }
        return rule;
    }

    private void audit(String action, String resourceId, RoutingRuleVO rule) {
        auditService.record(AuditCommandEntity.builder()
                .actor("admin").action(action).resourceType(RESOURCE_TYPE).resourceId(resourceId)
                .afterJson(rule == null ? null : JSON.toJSONString(rule))
                .build());
    }
}

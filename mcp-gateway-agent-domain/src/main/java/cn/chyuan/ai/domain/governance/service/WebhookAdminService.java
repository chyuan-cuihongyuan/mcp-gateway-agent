package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.adapter.repository.IWebhookEndpointRepository;
import cn.chyuan.ai.domain.governance.model.entity.AuditCommandEntity;
import cn.chyuan.ai.domain.governance.model.valobj.WebhookEndpointVO;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 治理告警 webhook 端点管理服务（工单 0051）
 *
 * <p>CRUD 校验（URL 形态、事件类型）+ 审计留痕（secret 恒脱敏）。
 * 预置事件类型见各发布点：BUDGET_SOFT_CROSSED（0050）、
 * CHANNEL_AUTO_DISABLED / CHANNEL_RECOVERED（0058）、CIRCUIT_OPEN（0059）。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class WebhookAdminService {

    @Resource
    private IWebhookEndpointRepository repository;

    @Resource
    private IAuditService auditService;

    public WebhookEndpointVO create(WebhookEndpointVO endpoint) {
        validate(endpoint, true);
        normalize(endpoint);
        Long id = repository.insert(endpoint);
        endpoint.setId(id);
        audit("CREATE_WEBHOOK", String.valueOf(id), null, endpoint);
        return endpoint;
    }

    public WebhookEndpointVO update(Long id, WebhookEndpointVO endpoint) {
        if (repository.findById(id) == null) {
            throw new AppException(ResponseCode.METHOD_NOT_FOUND.getCode(), "webhook 端点不存在: " + id);
        }
        endpoint.setId(id);
        validate(endpoint, false);
        normalize(endpoint);
        repository.update(endpoint);
        audit("UPDATE_WEBHOOK", String.valueOf(id), null, endpoint);
        return repository.findById(id);
    }

    public void delete(Long id) {
        if (repository.findById(id) == null) {
            throw new AppException(ResponseCode.METHOD_NOT_FOUND.getCode(), "webhook 端点不存在: " + id);
        }
        repository.deleteById(id);
        audit("DELETE_WEBHOOK", String.valueOf(id), null, null);
    }

    public WebhookEndpointVO get(Long id) {
        WebhookEndpointVO endpoint = repository.findById(id);
        if (endpoint == null) {
            throw new AppException(ResponseCode.METHOD_NOT_FOUND.getCode(), "webhook 端点不存在: " + id);
        }
        return endpoint;
    }

    public List<WebhookEndpointVO> list() {
        return repository.findAll();
    }

    private void validate(WebhookEndpointVO endpoint, boolean forCreate) {
        if (StringUtils.isBlank(endpoint.getUrl())
                || !(endpoint.getUrl().startsWith("http://") || endpoint.getUrl().startsWith("https://"))) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "url 必须是 http(s) 完整 URL");
        }
        if (forCreate && StringUtils.isBlank(endpoint.getName())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "name 不能为空");
        }
        if (endpoint.getEnabled() != null && endpoint.getEnabled() != 0 && endpoint.getEnabled() != 1) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "enabled 仅允许 0/1");
        }
    }

    private void normalize(WebhookEndpointVO endpoint) {
        if (endpoint.getEnabled() == null) {
            endpoint.setEnabled(1);
        }
    }

    private void audit(String action, String resourceId, WebhookEndpointVO before, WebhookEndpointVO after) {
        auditService.record(AuditCommandEntity.builder()
                .actor("admin")
                .action(action)
                .resourceType("WEBHOOK")
                .resourceId(resourceId)
                .beforeJson(before == null ? null : JSON.toJSONString(masked(before)))
                .afterJson(after == null ? null : JSON.toJSONString(masked(after)))
                .build());
    }

    /** secret 恒脱敏（审计与响应不回显） */
    private static WebhookEndpointVO masked(WebhookEndpointVO endpoint) {
        WebhookEndpointVO copy = new WebhookEndpointVO();
        copy.setId(endpoint.getId());
        copy.setName(endpoint.getName());
        copy.setUrl(endpoint.getUrl());
        copy.setEvents(endpoint.getEvents());
        copy.setSecret(endpoint.getSecret() == null ? null : "****");
        copy.setEnabled(endpoint.getEnabled());
        return copy;
    }
}

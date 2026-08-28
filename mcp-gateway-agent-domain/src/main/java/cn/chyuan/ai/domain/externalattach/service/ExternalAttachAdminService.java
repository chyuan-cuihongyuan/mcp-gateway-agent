package cn.chyuan.ai.domain.externalattach.service;

import cn.chyuan.ai.domain.externalattach.adapter.repository.IExternalAttachRepository;
import cn.chyuan.ai.domain.externalattach.model.valobj.ExternalAttachVO;
import cn.chyuan.ai.domain.governance.model.entity.AuditCommandEntity;
import cn.chyuan.ai.domain.governance.service.IAuditService;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 外部 MCP 挂接配置管理服务（工单 0021）
 *
 * <p>CRUD 校验（挂接名即工具前缀、传输参数完备性、超时边界）+ 审计留痕；
 * 连接测试与客户端生命周期归 {@code IExternalMcpAttachPort}。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class ExternalAttachAdminService implements IExternalAttachAdminService {

    /** 挂接名规则：与网关 ID 同口径，保证 {@code attachName_tool} 合法且无歧义 */
    private static final Pattern ATTACH_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");

    private static final int MIN_TIMEOUT_MS = 1_000;

    private static final int MAX_TIMEOUT_MS = 120_000;

    @Resource
    private IExternalAttachRepository repository;

    @Resource
    private IAuditService auditService;

    @Override
    public ExternalAttachVO create(ExternalAttachVO attach) {
        validate(attach, true);
        normalize(attach);
        Long id = repository.insert(attach);
        attach.setId(id);
        audit("CREATE_ATTACH", attach.getGatewayId() + "/" + attach.getAttachName(), null, attach);
        log.info("外部挂接已创建: gatewayId={}, attachName={}, transport={}",
                attach.getGatewayId(), attach.getAttachName(), attach.getTransportType());
        return attach;
    }

    @Override
    public ExternalAttachVO update(Long id, ExternalAttachVO attach) {
        ExternalAttachVO existing = repository.findById(id);
        if (existing == null) {
            throw new AppException(ResponseCode.METHOD_NOT_FOUND.getCode(), "挂接配置不存在: " + id);
        }
        attach.setId(id);
        // 网关与挂接名不可改（工具前缀是既有调用方的契约）
        attach.setGatewayId(existing.getGatewayId());
        attach.setAttachName(existing.getAttachName());
        validate(attach, false);
        normalize(attach);
        repository.update(attach);
        audit("UPDATE_ATTACH", existing.getGatewayId() + "/" + existing.getAttachName(), existing, attach);
        return repository.findById(id);
    }

    @Override
    public void delete(Long id) {
        ExternalAttachVO existing = repository.findById(id);
        if (existing == null) {
            throw new AppException(ResponseCode.METHOD_NOT_FOUND.getCode(), "挂接配置不存在: " + id);
        }
        repository.deleteById(id);
        audit("DELETE_ATTACH", existing.getGatewayId() + "/" + existing.getAttachName(), existing, null);
    }

    @Override
    public ExternalAttachVO get(Long id) {
        ExternalAttachVO attach = repository.findById(id);
        if (attach == null) {
            throw new AppException(ResponseCode.METHOD_NOT_FOUND.getCode(), "挂接配置不存在: " + id);
        }
        return attach;
    }

    @Override
    public List<ExternalAttachVO> listByGateway(String gatewayId) {
        return repository.findByGatewayId(gatewayId);
    }

    private void validate(ExternalAttachVO attach, boolean forCreate) {
        if (attach == null || StringUtils.isBlank(attach.getGatewayId())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "gatewayId不能为空");
        }
        if (forCreate && !ATTACH_NAME_PATTERN.matcher(StringUtils.defaultString(attach.getAttachName())).matches()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "attachName非法（字母数字开头，可含_-，长度1-64）");
        }
        String transport = attach.getTransportType();
        if (ExternalAttachVO.TRANSPORT_STREAMABLE_HTTP.equals(transport)) {
            String endpoint = attach.getEndpoint();
            if (StringUtils.isBlank(endpoint)
                    || !(endpoint.startsWith("http://") || endpoint.startsWith("https://"))) {
                throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                        "STREAMABLE_HTTP挂接的endpoint必须是http(s)完整URL");
            }
        } else if (ExternalAttachVO.TRANSPORT_STDIO.equals(transport)) {
            if (StringUtils.isBlank(attach.getCommand())) {
                throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "STDIO挂接的command不能为空");
            }
            if (StringUtils.isNotBlank(attach.getArgs())) {
                try {
                    JSON.parseArray(attach.getArgs());
                } catch (Exception e) {
                    throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "args必须是JSON数组字符串");
                }
            }
            if (StringUtils.isNotBlank(attach.getEnv()) && !isValidJsonObject(attach.getEnv())) {
                throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "env必须是JSON对象字符串");
            }
        } else {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "transportType仅支持STREAMABLE_HTTP/STDIO（SSE挂接已随0021下线）");
        }
        if (attach.getRequestTimeoutMs() != null
                && (attach.getRequestTimeoutMs() < MIN_TIMEOUT_MS || attach.getRequestTimeoutMs() > MAX_TIMEOUT_MS)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "requestTimeoutMs须在1000-120000毫秒之间");
        }
        if (attach.getStatus() != null && attach.getStatus() != 0 && attach.getStatus() != 1) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "status仅允许0(禁用)/1(启用)");
        }
    }

    private boolean isValidJsonObject(String text) {
        try {
            return JSON.parseObject(text) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private void normalize(ExternalAttachVO attach) {
        if (attach.getRequestTimeoutMs() == null) {
            attach.setRequestTimeoutMs(30_000);
        }
        if (attach.getStatus() == null) {
            attach.setStatus(1);
        }
    }

    private void audit(String action, String resourceId, ExternalAttachVO before, ExternalAttachVO after) {
        // 凭证不入审计（0017 脱敏口径）：快照前先剥离 apiKey
        auditService.record(AuditCommandEntity.builder()
                .actor("admin")
                .action(action)
                .resourceType("EXTERNAL_ATTACH")
                .resourceId(resourceId)
                .beforeJson(before == null ? null : JSON.toJSONString(masked(before)))
                .afterJson(after == null ? null : JSON.toJSONString(masked(after)))
                .build());
    }

    private static ExternalAttachVO masked(ExternalAttachVO attach) {
        ExternalAttachVO copy = new ExternalAttachVO();
        copy.setId(attach.getId());
        copy.setGatewayId(attach.getGatewayId());
        copy.setAttachName(attach.getAttachName());
        copy.setTransportType(attach.getTransportType());
        copy.setEndpoint(attach.getEndpoint());
        copy.setApiKey(attach.getApiKey() == null ? null : "****");
        copy.setCommand(attach.getCommand());
        copy.setArgs(attach.getArgs());
        copy.setEnv(attach.getEnv());
        copy.setRequestTimeoutMs(attach.getRequestTimeoutMs());
        copy.setStatus(attach.getStatus());
        copy.setConnectStatus(attach.getConnectStatus());
        copy.setConnectError(attach.getConnectError());
        copy.setConnectTime(attach.getConnectTime());
        return copy;
    }
}

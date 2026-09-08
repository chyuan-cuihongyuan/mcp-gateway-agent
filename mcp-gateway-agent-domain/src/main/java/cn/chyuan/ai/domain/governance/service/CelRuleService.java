package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.adapter.repository.ICelRuleRepository;
import cn.chyuan.ai.domain.governance.cache.TtlCache;
import cn.chyuan.ai.domain.governance.model.entity.AuditCommandEntity;
import cn.chyuan.ai.domain.governance.model.valobj.CelRuleVO;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.common.CelValidationResult;
import dev.cel.common.types.SimpleType;
import dev.cel.runtime.CelRuntime;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * CEL 规则服务（工单 0018 / 0011 决议）
 *
 * <p>变量面按 0011 决议声明为三个顶层 DYN 变量（auth / key / mcp），
 * 表达式内以选择符访问（如 auth.auth_type、key.quota.rpm_limit、mcp.tool.name）。
 * 保存时编译校验（0010 选型 cel-java 0.14.0，编译失败拒绝入库）；
 * 求值快照 30s TTL（0011 决策②：短 TTL 热更新，写操作即时失效本实例）。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class CelRuleService implements ICelRuleService {

    /** 顶层变量名（0011 CEL 变量面的三个命名空间 + 0048 扩展 jwt/client） */
    static final String VAR_AUTH = "auth";
    static final String VAR_KEY = "key";
    static final String VAR_MCP = "mcp";
    static final String VAR_JWT = "jwt";
    static final String VAR_CLIENT = "client";

    @Resource
    private ICelRuleRepository repository;

    @Resource
    private IAuditService auditService;

    /** 快照 TTL（秒），0011 决策②默认 30s */
    @Value("${governance.cache.ttl-seconds:30}")
    private long cacheTtlSeconds;

    private TtlCache<List<CompiledCelRule>> snapshotCache;

    /** Cel 为不可变 bundle，线程安全，进程内共享 */
    private final Cel cel = buildCel();

    @PostConstruct
    public void init() {
        snapshotCache = new TtlCache<>(cacheTtlSeconds * 1000);
    }

    Cel getCel() {
        return cel;
    }

    @Override
    public String validateExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            return "表达式不能为空";
        }
        try {
            CelValidationResult result = cel.compile(expression);
            return result.hasError() ? result.getIssueString() : null;
        } catch (Exception e) {
            return e.getMessage() == null ? "表达式编译失败" : e.getMessage();
        }
    }

    @Override
    public CelRuleVO create(CelRuleVO rule) {
        validateForSave(rule);
        repository.insert(rule);
        audit("CREATE_RULE", String.valueOf(rule.getId()), null, rule);
        invalidateSnapshot();
        return rule;
    }

    @Override
    public CelRuleVO update(CelRuleVO rule) {
        validateForSave(rule);
        CelRuleVO before = repository.findById(rule.getId());
        if (before == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "规则不存在: " + rule.getId());
        }
        repository.update(rule);
        audit("UPDATE_RULE", String.valueOf(rule.getId()), before, rule);
        invalidateSnapshot();
        return repository.findById(rule.getId());
    }

    @Override
    public void delete(Long id) {
        CelRuleVO before = repository.findById(id);
        if (before == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "规则不存在: " + id);
        }
        repository.deleteById(id);
        audit("DELETE_RULE", String.valueOf(id), before, null);
        invalidateSnapshot();
    }

    @Override
    public CelRuleVO getById(Long id) {
        return repository.findById(id);
    }

    @Override
    public List<CelRuleVO> page(String keyword, int page, int size) {
        return repository.findByPage(keyword == null ? "" : keyword, Math.max(0, (page - 1) * size), size);
    }

    @Override
    public long count(String keyword) {
        return repository.count(keyword == null ? "" : keyword);
    }

    @Override
    public List<CompiledCelRule> activeRuleSnapshot() {
        return snapshotCache.get("active", () -> repository.findAllActive().stream()
                .map(rule -> {
                    CelRuntime.Program program = null;
                    try {
                        CelValidationResult compiled = cel.compile(rule.getExpression());
                        if (!compiled.hasError()) {
                            program = cel.createProgram(compiled.getAst());
                        }
                    } catch (Exception e) {
                        // createProgram 失败同样落入 fail-closed 分支
                        log.error("CEL 规则快照构建失败 ruleId={}: {}", rule.getId(), e.getMessage());
                    }
                    if (program == null) {
                        // 库中规则被绕过校验改坏：保留为 fail-closed 拒绝规则，不静默放行
                        log.error("CEL 规则快照编译失败（求值将按 fail-closed 拒绝）ruleId={} name={}",
                                rule.getId(), rule.getRuleName());
                    }
                    return new CompiledCelRule(rule.getId(), rule.getRuleName(), rule.getScopeType(),
                            rule.getGatewayId(), rule.getVirtualKeyId(), program);
                })
                .toList());
    }

    /** 写路径即时失效本实例快照（0011 决策②：同实例写入即时生效） */
    public void invalidateSnapshot() {
        if (snapshotCache != null) {
            snapshotCache.invalidateAll();
        }
    }

    private void validateForSave(CelRuleVO rule) {
        checkScopeTarget(rule);
        if (rule == null || rule.getExpression() == null || rule.getExpression().isBlank()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "CEL 表达式不能为空");
        }
        String error = validateExpression(rule.getExpression());
        if (error != null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "CEL 表达式编译失败: " + error);
        }
    }

    /** 作用域目标完整性：GATEWAY 需 gatewayId；VIRTUAL_KEY 需 virtualKeyId；取值合法 */
    private void checkScopeTarget(CelRuleVO rule) {
        if (rule == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "规则不能为空");
        }
        String scope = rule.getScopeType() == null ? "" : rule.getScopeType();
        if ("GATEWAY".equals(scope) && (rule.getGatewayId() == null || rule.getGatewayId().isBlank())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "scope=GATEWAY 的规则必须指定 gatewayId");
        }
        if ("VIRTUAL_KEY".equals(scope) && rule.getVirtualKeyId() == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "scope=VIRTUAL_KEY 的规则必须指定 virtualKeyId");
        }
        if (!"GLOBAL".equals(scope) && !"GATEWAY".equals(scope) && !"VIRTUAL_KEY".equals(scope)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "scopeType 必须为 GLOBAL / GATEWAY / VIRTUAL_KEY");
        }
    }

    private void audit(String action, String resourceId, CelRuleVO before, CelRuleVO after) {
        auditService.record(AuditCommandEntity.builder()
                .actor("admin")
                .action(action)
                .resourceType("CEL_RULE")
                .resourceId(resourceId)
                .beforeJson(before == null ? null : snapshotOf(before))
                .afterJson(after == null ? null : snapshotOf(after))
                .build());
    }

    private String snapshotOf(CelRuleVO rule) {
        return "{\"id\":" + rule.getId()
                + ",\"ruleName\":" + quote(rule.getRuleName())
                + ",\"expression\":" + quote(rule.getExpression())
                + ",\"scopeType\":" + quote(rule.getScopeType())
                + ",\"gatewayId\":" + quote(rule.getGatewayId())
                + ",\"virtualKeyId\":" + rule.getVirtualKeyId()
                + ",\"status\":" + quote(rule.getStatus()) + "}";
    }

    private String quote(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private Cel buildCel() {
        return CelFactory.standardCelBuilder()
                .addVar(VAR_AUTH, SimpleType.DYN)
                .addVar(VAR_KEY, SimpleType.DYN)
                .addVar(VAR_MCP, SimpleType.DYN)
                .addVar(VAR_JWT, SimpleType.DYN)
                .addVar(VAR_CLIENT, SimpleType.DYN)
                .build();
    }

    @Override
    public DryRunResult dryRun(String expression, java.util.Map<String, Object> variables) {
        if (expression == null || expression.isBlank()) {
            return DryRunResult.compileError("表达式不能为空");
        }
        CelValidationResult compiled;
        try {
            compiled = cel.compile(expression);
        } catch (Exception e) {
            return DryRunResult.compileError(e.getMessage() == null ? "表达式编译失败" : e.getMessage());
        }
        if (compiled.hasError()) {
            return DryRunResult.compileError(compiled.getIssueString());
        }
        try {
            Object result = cel.createProgram(compiled.getAst())
                    .eval(variables == null ? java.util.Map.of() : variables);
            if (result instanceof Boolean b) {
                return b ? DryRunResult.pass() : DryRunResult.denied();
            }
            return DryRunResult.runtimeError("表达式结果非布尔: " + result);
        } catch (Exception e) {
            return DryRunResult.runtimeError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }
}

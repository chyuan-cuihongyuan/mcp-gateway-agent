package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.adapter.repository.ICelTemplateRepository;
import cn.chyuan.ai.domain.governance.model.entity.AuditCommandEntity;
import cn.chyuan.ai.domain.governance.model.valobj.CelRuleTemplateVO;
import cn.chyuan.ai.domain.governance.model.valobj.CelRuleVO;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import cn.chyuan.ai.types.exception.AppException;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CEL 规则模板服务（工单 0057）
 *
 * <p>内置模板启动幂等种子（builtin=1 不可删改）；实例化 = 渲染占位符 →
 * 复用规则保存编译校验（非法渲染拒绝）；用户自建模板可增删。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class CelTemplateAdminService {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z0-9_]+)\\}\\}");

    @Resource
    private ICelTemplateRepository repository;

    @Resource
    private ICelRuleService celRuleService;

    @Resource
    private IAuditService auditService;

    public List<CelRuleTemplateVO> list() {
        return repository.findAll();
    }

    public CelRuleTemplateVO createCustom(CelRuleTemplateVO template) {
        if (template == null || StringUtils.isBlank(template.getCode())
                || StringUtils.isBlank(template.getName())
                || StringUtils.isBlank(template.getExpression())) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "code/name/expression 均不能为空");
        }
        if (template.getCode().startsWith("builtin-")) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "builtin- 前缀为内置模板保留");
        }
        if (repository.findByCode(template.getCode()) != null) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "模板编码已存在: " + template.getCode());
        }
        // 表达式骨架本身必须可编译（占位符替换为占位值后校验）
        String probe = render(template.getExpression(), probeParams(template.getExpression()));
        String error = celRuleService.validateExpression(probe);
        if (error != null) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "模板表达式骨架非法: " + error);
        }
        template.setBuiltin(0);
        Long id = repository.insert(template);
        template.setId(id);
        audit("CREATE_TEMPLATE", template.getCode(), template);
        return template;
    }

    public void delete(Long id) {
        CelRuleTemplateVO existing = requireTemplate(id);
        if (existing.getBuiltin() != null && existing.getBuiltin() == 1) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "内置模板不可删除: " + existing.getCode());
        }
        repository.deleteById(id);
        audit("DELETE_TEMPLATE", existing.getCode(), existing);
    }

    /**
     * 实例化：渲染占位符 → 组装规则 → 走既有保存校验链。
     *
     * @return 创建成功的规则
     */
    public CelRuleVO instantiate(String code, String ruleName, String scopeType, String gatewayId,
            Long virtualKeyId, Map<String, String> params) {
        CelRuleTemplateVO template = repository.findByCode(code);
        if (template == null) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "模板不存在: " + code);
        }
        String expression = render(template.getExpression(), params == null ? Map.of() : params);
        // 渲染后必须无残留占位符（半渲染拒绝）
        Matcher left = PLACEHOLDER.matcher(expression);
        if (left.find()) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS,
                    "参数缺失：占位符 " + left.group() + " 未替换（完整变量说明见模板 variablesDesc）");
        }
        CelRuleVO rule = CelRuleVO.builder()
                .ruleName(StringUtils.isBlank(ruleName) ? template.getName() : ruleName)
                .expression(expression)
                .scopeType(StringUtils.isBlank(scopeType) ? "GLOBAL" : scopeType)
                .gatewayId(gatewayId)
                .virtualKeyId(virtualKeyId)
                .status("ACTIVE")
                .build();
        CelRuleVO created = celRuleService.create(rule);
        audit("INSTANTIATE_TEMPLATE", code, template);
        return created;
    }

    /** 渲染：未提供的占位符原样保留（由调用侧校验残留） */
    static String render(String expression, Map<String, String> params) {
        Matcher matcher = PLACEHOLDER.matcher(expression);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String replacement = params.get(matcher.group(1));
            matcher.appendReplacement(rendered,
                    java.util.regex.Matcher.quoteReplacement(replacement == null ? matcher.group() : replacement));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    /** 骨架校验用占位参数（每个占位符给合法假想值） */
    private static Map<String, String> probeParams(String expression) {
        Map<String, String> probes = new java.util.HashMap<>();
        Matcher matcher = PLACEHOLDER.matcher(expression);
        while (matcher.find()) {
            probes.putIfAbsent(matcher.group(1), "\"probe\"");
        }
        return probes;
    }

    private CelRuleTemplateVO requireTemplate(Long id) {
        CelRuleTemplateVO template = repository.findById(id);
        if (template == null) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "模板不存在: " + id);
        }
        return template;
    }

    private void audit(String action, String resourceId, CelRuleTemplateVO template) {
        auditService.record(AuditCommandEntity.builder()
                .actor("admin").action(action).resourceType("CEL_TEMPLATE").resourceId(resourceId)
                .afterJson(template == null ? null : JSON.toJSONString(template))
                .build());
    }
}

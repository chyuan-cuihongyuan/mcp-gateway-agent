package cn.chyuan.ai.domain.promptresource.service;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import cn.chyuan.ai.domain.governance.service.ICelEvaluationService;
import cn.chyuan.ai.domain.promptresource.adapter.repository.IPromptResourceRepository;
import cn.chyuan.ai.domain.promptresource.model.valobj.PromptVO;
import cn.chyuan.ai.domain.promptresource.model.valobj.ResourceVO;
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
 * 网关本地 Prompt/Resource 服务（工单 0053）
 *
 * <p>prompts/list、prompts/get、resources/list、resources/read 四方法的域逻辑：
 * 启用态清单（CEL 裁剪复用 tools 域变量面——mcp.tool.name=提示/资源名、
 * mcp.tool.source/target=PROMPT/RESOURCE，字典文档 15 口径）；模板参数渲染；
 * 缺参/未找到结构化错误。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class PromptResourceService {

    /** CEL 变量面：提示域（经 tools 域变量承载，见 docs/03-mcp-gateway-agent/15） */
    public static final String SOURCE_PROMPT = "PROMPT";

    /** CEL 变量面：资源域 */
    public static final String SOURCE_RESOURCE = "RESOURCE";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z0-9_]+)\\}\\}");

    @Resource
    private IPromptResourceRepository repository;

    @Resource
    private ICelEvaluationService celEvaluationService;

    /** CEL 可见的启用态 Prompt 清单 */
    public List<PromptVO> visiblePrompts(String gatewayId, GovernancePrincipal principal) {
        return repository.findPrompts(gatewayId).stream()
                .filter(p -> p.getStatus() == null || p.getStatus() == 1)
                .filter(p -> celEvaluationService.isToolAllowed(principal, gatewayId,
                        "prompts/list", p.getName(), SOURCE_PROMPT))
                .toList();
    }

    /** CEL 可见的启用态 Resource 清单 */
    public List<ResourceVO> visibleResources(String gatewayId, GovernancePrincipal principal) {
        return repository.findResources(gatewayId).stream()
                .filter(r -> r.getStatus() == null || r.getStatus() == 1)
                .filter(r -> celEvaluationService.isToolAllowed(principal, gatewayId,
                        "resources/list", r.getUri(), SOURCE_RESOURCE))
                .toList();
    }

    /** prompts/get：CEL 门槛 + 模板实参渲染 */
    public RenderedPrompt getPrompt(String gatewayId, String name, Map<String, String> arguments,
            GovernancePrincipal principal) {
        if (!celEvaluationService.isToolAllowed(principal, gatewayId,
                "prompts/get", name, SOURCE_PROMPT)) {
            throw new AppException(McpErrorCodes.INSUFFICIENT_PERMISSIONS, "无权访问该提示: " + name);
        }
        PromptVO prompt = repository.findPrompt(gatewayId, name);
        if (prompt == null || (prompt.getStatus() != null && prompt.getStatus() != 1)) {
            throw new AppException(McpErrorCodes.RESOURCE_NOT_FOUND, "提示未找到: " + name);
        }
        List<Map<String, Object>> declared = declaredArguments(prompt.getArgumentsJson());
        for (Map<String, Object> arg : declared) {
            if (Boolean.TRUE.equals(arg.get("required"))
                    && (arguments == null || StringUtils.isBlank(arguments.get(String.valueOf(arg.get("name")))))) {
                throw new AppException(McpErrorCodes.INVALID_PARAMS,
                        "缺少必填参数: " + arg.get("name"));
            }
        }
        String rendered = renderTemplate(prompt.getTemplate(), arguments == null ? Map.of() : arguments);
        return new RenderedPrompt(prompt, rendered);
    }

    /** resources/read：CEL 门槛 */
    public ResourceVO readResource(String gatewayId, String uri, GovernancePrincipal principal) {
        if (!celEvaluationService.isToolAllowed(principal, gatewayId,
                "resources/read", uri, SOURCE_RESOURCE)) {
            throw new AppException(McpErrorCodes.INSUFFICIENT_PERMISSIONS, "无权访问该资源: " + uri);
        }
        ResourceVO resource = repository.findResource(gatewayId, uri);
        if (resource == null || (resource.getStatus() != null && resource.getStatus() != 1)) {
            throw new AppException(McpErrorCodes.RESOURCE_NOT_FOUND, "资源未找到: " + uri);
        }
        return resource;
    }

    /** 模板渲染：未提供实参的占位符保留原样（管理员调试可见） */
    static String renderTemplate(String template, Map<String, String> arguments) {
        if (template == null) {
            return "";
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String value = arguments.get(matcher.group(1));
            matcher.appendReplacement(rendered,
                    Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> declaredArguments(String argumentsJson) {
        if (StringUtils.isBlank(argumentsJson)) {
            return List.of();
        }
        try {
            return JSON.parseObject(argumentsJson, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** prompts/get 结果（声明 + 渲染消息） */
    public record RenderedPrompt(PromptVO prompt, String renderedText) {
    }
}

package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.governance.adapter.IGovernanceEventPublisher;
import cn.chyuan.ai.domain.promptresource.service.PromptTemplateRenderer;
import cn.chyuan.ai.domain.promptresource.service.PromptVersionService;
import cn.chyuan.ai.domain.promptresource.service.PromptVersionService.PromptVersion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 提示版本管理控制器（工单 0196 AA1，借鉴 Langfuse prompt management）—
 * GET /admin/v1/prompt-versions（全量/按名）、GET /admin/v1/prompt-versions/{name}/published（解析已发布）、
 * POST /admin/v1/prompt-versions（建草稿）、POST /admin/v1/prompt-versions/{name}/{version}/publish（发布）、
 * POST /admin/v1/prompt-versions/{name}/{version}/rollback（回滚）。
 * 变更经 PROMPT_VERSION_CHANGE 事件留痕。
 */
@Slf4j
@RestController
@CrossOrigin(origins = {"http://localhost:3000"})
@RequestMapping("/admin/v1/prompt-versions")
public class PromptVersionController {

    private final PromptVersionService promptVersionService;
    private final IGovernanceEventPublisher eventPublisher;
    private final PromptTemplateRenderer templateRenderer;

    public PromptVersionController(PromptVersionService promptVersionService,
            IGovernanceEventPublisher eventPublisher, PromptTemplateRenderer templateRenderer) {
        this.promptVersionService = promptVersionService;
        this.eventPublisher = eventPublisher;
        this.templateRenderer = templateRenderer;
    }

    @GetMapping
    public Response<List<Map<String, Object>>> list(@RequestParam(required = false) String name) {
        List<PromptVersion> versions = (name == null || name.isBlank())
                ? promptVersionService.listAll()
                : promptVersionService.list(name.trim());
        return Response.success(versions.stream().map(PromptVersionController::toMap).toList());
    }

    @GetMapping("/{name}/published")
    public Response<Map<String, Object>> published(@PathVariable String name) {
        PromptVersion published = promptVersionService.resolvePublished(name);
        if (published == null) {
            return Response.fail("0002", "提示不存在: " + name);
        }
        return Response.success(toMap(published));
    }

    @PostMapping
    public Response<Map<String, Object>> createDraft(@RequestParam String promptName,
            @RequestParam String template,
            @RequestParam(required = false) String note,
            @RequestParam(required = false) String operator) {
        try {
            PromptVersion draft = promptVersionService.createDraft(promptName.trim(), template, note, operator);
            publishChange("DRAFT_CREATED", draft);
            return Response.success(toMap(draft));
        } catch (IllegalArgumentException e) {
            return Response.fail("0002", e.getMessage());
        }
    }

    @PostMapping("/{name}/{version}/publish")
    public Response<Map<String, Object>> publish(@PathVariable String name, @PathVariable int version,
            @RequestParam(required = false) String operator) {
        try {
            PromptVersion published = promptVersionService.publish(name, version, operator);
            publishChange("PUBLISHED", published);
            return Response.success(toMap(published));
        } catch (IllegalArgumentException e) {
            return Response.fail("0002", e.getMessage());
        }
    }

    @PostMapping("/{name}/{version}/rollback")
    public Response<Map<String, Object>> rollback(@PathVariable String name, @PathVariable int version,
            @RequestParam(required = false) String operator) {
        try {
            PromptVersion rolled = promptVersionService.rollback(name, version, operator);
            publishChange("ROLLBACK", rolled);
            return Response.success(toMap(rolled));
        } catch (IllegalArgumentException e) {
            return Response.fail("0002", e.getMessage());
        }
    }

    /** AA2（0197）：打标签（同名单一标签持有） */
    @PostMapping("/{name}/{version}/label")
    public Response<Map<String, Object>> attachLabel(@PathVariable String name, @PathVariable int version,
            @RequestParam String label) {
        try {
            PromptVersion labeled = promptVersionService.attachLabel(name, version, label);
            publishChange("LABELED", labeled);
            return Response.success(toMap(labeled));
        } catch (IllegalArgumentException e) {
            return Response.fail("0002", e.getMessage());
        }
    }

    /** AA2（0197）：标签解析（label → 版本；未打标回退已发布/最高版本） */
    @GetMapping("/{name}/resolve")
    public Response<Map<String, Object>> resolveByLabel(@PathVariable String name,
            @RequestParam String label) {
        PromptVersion resolved = promptVersionService.resolveByLabel(name, label);
        if (resolved == null) {
            return Response.fail("0002", "提示不存在: " + name);
        }
        return Response.success(toMap(resolved));
    }

    /** AA3（0198）：模板渲染预览（严格语义：变量缺失/嵌套占位报错） */
    @PostMapping("/{name}/{version}/render")
    public Response<Map<String, Object>> render(@PathVariable String name, @PathVariable int version,
            @RequestParam(required = false) String varsJson) {
        try {
            PromptVersion target = promptVersionService.get(name, version);
            Map<String, String> vars = parseVars(varsJson);
            String rendered = templateRenderer.render(target.template(), vars);
            return Response.success(Map.of(
                    "promptName", name,
                    "version", version,
                    "rendered", rendered,
                    "declaredVariables", PromptTemplateRenderer.extractVariables(target.template()),
                    "cached", templateRenderer.cacheSize()));
        } catch (IllegalArgumentException e) {
            return Response.fail("0002", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> parseVars(String varsJson) {
        if (varsJson == null || varsJson.isBlank()) {
            return Map.of();
        }
        try {
            return com.alibaba.fastjson.JSON.parseObject(varsJson, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("varsJson 不是合法 JSON 对象");
        }
    }

    private void publishChange(String action, PromptVersion version) {
        try {
            eventPublisher.publish("PROMPT_VERSION_CHANGE", Map.of(
                    "action", action,
                    "promptName", version.promptName(),
                    "version", String.valueOf(version.version()),
                    "operator", version.operator() == null ? "unknown" : version.operator()));
        } catch (Exception ignored) {
            // 事件尽力而为
        }
    }

    static Map<String, Object> toMap(PromptVersion v) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", v.id());
        map.put("promptName", v.promptName());
        map.put("version", v.version());
        map.put("status", v.status());
        map.put("note", v.note());
        map.put("operator", v.operator());
        return map;
    }
}

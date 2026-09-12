package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.generation.service.AnnotationReplyService;
import cn.chyuan.ai.domain.generation.service.AnnotationReplyService.AnnotationQa;
import cn.chyuan.ai.domain.generation.service.AnnotationReplyService.AnnotationStore;
import cn.chyuan.ai.domain.generation.service.GenerationGuardPipeline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 标注回复管理控制器（工单 0202 AA7）—
 * GET /admin/v1/annotation-qa（启用清单）、POST（新增问答对，question_key 归一化唯一）、
 * POST /{id}/disable、POST /{id}/enable、DELETE /{id}、POST /test-hit（命中试测，不计数）、
 * GET /stats（管线观测计数）。变更经 ANNOTATION_QA_CHANGE 事件留痕。
 */
@Slf4j
@RestController
@CrossOrigin(origins = {"http://localhost:3000"})
@RequestMapping("/admin/v1/annotation-qa")
public class AnnotationQaController {

    private final AnnotationReplyService annotationReplyService;
    private final AnnotationStore annotationStore;
    private final GenerationGuardPipeline generationGuardPipeline;
    private final cn.chyuan.ai.domain.governance.adapter.IGovernanceEventPublisher eventPublisher;

    public AnnotationQaController(AnnotationReplyService annotationReplyService,
            AnnotationStore annotationStore, GenerationGuardPipeline generationGuardPipeline,
            cn.chyuan.ai.domain.governance.adapter.IGovernanceEventPublisher eventPublisher) {
        this.annotationReplyService = annotationReplyService;
        this.annotationStore = annotationStore;
        this.generationGuardPipeline = generationGuardPipeline;
        this.eventPublisher = eventPublisher;
    }

    @GetMapping
    public Response<List<Map<String, Object>>> list() {
        return Response.success(annotationStore.listEnabled().stream()
                .map(AnnotationQaController::toMap).toList());
    }

    @PostMapping
    public Response<Map<String, Object>> create(@RequestParam String question,
            @RequestParam String answer,
            @RequestParam(required = false) String operator) {
        try {
            AnnotationQa qa = new AnnotationQa(null, question, answer, 0, true, operator);
            annotationStore.insert(qa);
            publishChange("CREATED", question);
            return Response.success(toMap(qa));
        } catch (IllegalArgumentException e) {
            return Response.fail("0002", e.getMessage());
        }
    }

    @PostMapping("/{id}/disable")
    public Response<Map<String, Object>> disable(@PathVariable Long id,
            @RequestParam(required = false) String operator) {
        return toggle(id, false, operator);
    }

    @PostMapping("/{id}/enable")
    public Response<Map<String, Object>> enable(@PathVariable Long id,
            @RequestParam(required = false) String operator) {
        return toggle(id, true, operator);
    }

    @DeleteMapping("/{id}")
    public Response<Map<String, Object>> delete(@PathVariable Long id) {
        annotationStore.delete(id);
        publishChange("DELETED", String.valueOf(id));
        return Response.success(Map.of("id", id, "deleted", true));
    }

    /** 命中试测：走真实归一化/编辑距离链路但不计数（运营校验用） */
    @PostMapping("/test-hit")
    public Response<Map<String, Object>> testHit(@RequestParam String question) {
        AnnotationReplyService.AnnotationHit hit = annotationReplyService.find(question);
        if (hit == null) {
            return Response.success(Map.of("hit", false));
        }
        return Response.success(Map.of(
                "hit", true,
                "answer", hit.answer(),
                "matchedQuestion", hit.matchedQuestion(),
                "fuzzy", hit.fuzzy()));
    }

    /** 生成治理管线观测计数 */
    @GetMapping("/stats")
    public Response<Map<String, Long>> stats() {
        return Response.success(generationGuardPipeline.stats());
    }

    private Response<Map<String, Object>> toggle(Long id, boolean enabled, String operator) {
        AnnotationQa current = annotationStore.findById(id);
        if (current == null) {
            return Response.fail("0002", "问答对不存在: " + id);
        }
        AnnotationQa updated = new AnnotationQa(current.id(), current.question(), current.answer(),
                current.hitCount(), enabled, current.operator());
        annotationStore.update(updated);
        publishChange(enabled ? "ENABLED" : "DISABLED", current.question());
        return Response.success(toMap(updated));
    }

    private void publishChange(String action, String question) {
        try {
            eventPublisher.publish("ANNOTATION_QA_CHANGE", Map.of(
                    "action", action,
                    "questionKey", AnnotationReplyService.normalizeKey(question)));
        } catch (Exception ignored) {
            // 事件尽力而为
        }
    }

    static Map<String, Object> toMap(AnnotationQa qa) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", qa.id());
        map.put("question", qa.question());
        map.put("answer", qa.answer());
        map.put("hitCount", qa.hitCount());
        map.put("enabled", qa.enabled());
        map.put("operator", qa.operator());
        return map;
    }
}

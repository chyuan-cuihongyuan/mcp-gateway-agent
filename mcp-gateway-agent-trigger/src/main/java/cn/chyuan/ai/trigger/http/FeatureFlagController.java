package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.domain.governance.service.FeatureFlagService;
import cn.chyuan.ai.api.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 特性开关管理控制器（工单 0178 Y2）—
 * GET /admin/v1/flags/{key}（评估：未注册默认关）、GET /admin/v1/flags（全量）、
 * POST /admin/v1/flags（注册/更新，FLAG_CHANGE 事件留痕）。
 */
@Slf4j
@RestController
@CrossOrigin(origins = {"http://localhost:3000"})
@RequestMapping("/admin/v1/flags")
public class FeatureFlagController {

    private final FeatureFlagService featureFlagService;
    private final cn.chyuan.ai.domain.governance.adapter.IGovernanceEventPublisher eventPublisher;

    public FeatureFlagController(FeatureFlagService featureFlagService,
                                 cn.chyuan.ai.domain.governance.adapter.IGovernanceEventPublisher eventPublisher) {
        this.featureFlagService = featureFlagService;
        this.eventPublisher = eventPublisher;
    }

    @GetMapping("/{key}")
    public Response<Map<String, Object>> evaluate(@PathVariable String key) {
        return Response.success(Map.of(
                "key", key,
                "enabled", featureFlagService.isEnabled(key)));
    }

    @GetMapping
    public Response<Map<String, Boolean>> list() {
        return Response.success(featureFlagService.listAll());
    }

    @PostMapping
    public Response<Map<String, Object>> upsert(@RequestParam String flagKey,
                                                @RequestParam boolean enabled,
                                                @RequestParam(required = false) String note,
                                                @RequestParam(required = false) String operator) {
        if (flagKey == null || flagKey.isBlank()) {
            return Response.fail("0002", "flagKey 不能为空");
        }
        featureFlagService.upsert(flagKey.trim(), enabled, note, operator);
        try {
            eventPublisher.publish("FLAG_CHANGE", Map.of(
                    "flagKey", flagKey.trim(), "enabled", String.valueOf(enabled),
                    "operator", operator == null ? "unknown" : operator));
        } catch (Exception ignored) {
            // 事件尽力而为
        }
        return Response.success(Map.of("flagKey", flagKey.trim(), "enabled", enabled));
    }
}

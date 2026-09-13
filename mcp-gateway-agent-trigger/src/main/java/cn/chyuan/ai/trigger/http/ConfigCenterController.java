package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.configcenter.service.ConfigBundleCodec;
import cn.chyuan.ai.domain.configcenter.service.ConfigCenterFacade;
import cn.chyuan.ai.domain.configcenter.service.ConfigDigestCalculator;
import cn.chyuan.ai.domain.configcenter.service.ConfigGrayRouter;
import cn.chyuan.ai.domain.configcenter.service.ConfigGrayRouter.GrayRule;
import cn.chyuan.ai.domain.configcenter.service.ConfigGrayRouter.Resolved;
import cn.chyuan.ai.domain.configcenter.service.ConfigHealthEvaluator;
import cn.chyuan.ai.domain.configcenter.service.ConfigHealthEvaluator.ConfigHealthService;
import cn.chyuan.ai.domain.configcenter.service.ConfigHealthEvaluator.HealthSummary;
import cn.chyuan.ai.domain.configcenter.service.ConfigHealthEvaluator.HealthInputs;
import cn.chyuan.ai.domain.configcenter.service.ConfigListenerHub;
import cn.chyuan.ai.domain.configcenter.service.ConfigSchemaGate;
import cn.chyuan.ai.domain.configcenter.service.ConfigSnapshotService;
import cn.chyuan.ai.domain.configcenter.service.ConfigSnapshotService.ConfigSnapshot;
import cn.chyuan.ai.domain.configcenter.service.DriftDetector;
import cn.chyuan.ai.domain.configcenter.service.DriftDetector.DriftReport;
import cn.chyuan.ai.domain.governance.adapter.IGovernanceEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 配置中心控制器（工单 0251-0259 AG 簇，借鉴 Nacos/Apollo/Argo CD/Terraform 思想）—
 * 发布/读取/时间线/回滚（AG1）、plan 预览（AG2）、schema 注册（AG3）、长轮询监听（AG5）、
 * 灰度规则与解析（AG6）、健康四态（AG7）、漂移报告（AG8）、bundle 导入导出（AG9）。
 * 变更经 CONFIG_CENTER_CHANGE 事件留痕。
 */
@Slf4j
@RestController
@CrossOrigin(origins = {"http://localhost:3000"})
@RequestMapping("/admin/v1/config-center")
public class ConfigCenterController {

    private final ConfigCenterFacade facade;
    private final ConfigSnapshotService snapshotService;
    private final ConfigSchemaGate schemaGate;
    private final ConfigListenerHub listenerHub;
    private final ConfigGrayRouter grayRouter;
    private final ConfigHealthService healthService;
    private final DriftDetector driftDetector;
    private final IGovernanceEventPublisher eventPublisher;

    /** AG5：长轮询挂起开关（默认 false——listen 端点退化为即时比对，零挂起行为） */
    @Value("${config.center.listener.enabled:false}")
    private boolean listenerEnabled;

    public ConfigCenterController(ConfigCenterFacade facade, ConfigSnapshotService snapshotService,
            ConfigSchemaGate schemaGate, ConfigListenerHub listenerHub, ConfigGrayRouter grayRouter,
            ConfigHealthService healthService, DriftDetector driftDetector,
            IGovernanceEventPublisher eventPublisher) {
        this.facade = facade;
        this.snapshotService = snapshotService;
        this.schemaGate = schemaGate;
        this.listenerHub = listenerHub;
        this.grayRouter = grayRouter;
        this.healthService = healthService;
        this.driftDetector = driftDetector;
        this.eventPublisher = eventPublisher;
    }

    /** AG1：发布（schema 门 + 敏感加密由门面编排） */
    @PostMapping("/publish")
    public Response<Map<String, Object>> publish(@RequestParam String namespace,
            @RequestParam String configKey, @RequestParam String content,
            @RequestParam(defaultValue = "false") boolean sensitive,
            @RequestParam(required = false) String publisher,
            @RequestParam(required = false) String note) {
        try {
            ConfigSnapshot snapshot = facade.publish(namespace.trim(), configKey.trim(), content,
                    sensitive, publisher, note);
            publishChange("PUBLISHED", namespace, configKey, publisher);
            return Response.success(toMap(snapshot));
        } catch (IllegalArgumentException e) {
            return Response.fail("0002", e.getMessage());
        }
    }

    /** AG1：读取当前生效配置（敏感项透明解密） */
    @GetMapping("/current")
    public Response<Map<String, Object>> current(@RequestParam String namespace,
            @RequestParam String configKey) {
        String content = facade.resolve(namespace, configKey);
        if (content == null) {
            return Response.fail("0002", "配置不存在: " + namespace + "/" + configKey);
        }
        Map<String, Object> out = new HashMap<>();
        out.put("namespace", namespace);
        out.put("configKey", configKey);
        out.put("content", content);
        return Response.success(out);
    }

    /** AG1：同键版本时间线（倒序） */
    @GetMapping("/timeline")
    public Response<List<Map<String, Object>>> timeline(@RequestParam String namespace,
            @RequestParam String configKey) {
        return Response.success(snapshotService.timeline(namespace, configKey).stream()
                .map(ConfigCenterController::toMap).toList());
    }

    /** AG1：回滚到指定版本 */
    @PostMapping("/rollback")
    public Response<Map<String, Object>> rollback(@RequestParam String namespace,
            @RequestParam String configKey, @RequestParam int toVersion,
            @RequestParam(required = false) String publisher,
            @RequestParam(required = false) String note) {
        try {
            ConfigSnapshot snapshot = facade.rollback(namespace, configKey, toVersion, publisher, note);
            publishChange("ROLLBACK", namespace, configKey, publisher);
            return Response.success(toMap(snapshot));
        } catch (IllegalArgumentException e) {
            return Response.fail("0002", e.getMessage());
        }
    }

    /** AG2：plan 预览（当前 vs 期望，无副作用） */
    @PostMapping("/plan")
    public Response<Map<String, Object>> plan(@RequestParam String namespace,
            @RequestParam String configKey, @RequestBody String incomingJson) {
        try {
            String current = facade.resolve(namespace, configKey);
            cn.chyuan.ai.domain.configcenter.service.ConfigPlanDiffer.PlanDiff diff =
                    cn.chyuan.ai.domain.configcenter.service.ConfigPlanDiffer.diff(
                            current == null ? "{}" : current, incomingJson);
            Map<String, Object> out = new HashMap<>();
            out.put("rows", diff.rows());
            out.put("risks", diff.risks());
            out.put("added", diff.added());
            out.put("removed", diff.removed());
            out.put("changed", diff.changed());
            out.put("unchanged", diff.unchanged());
            return Response.success(out);
        } catch (IllegalArgumentException e) {
            return Response.fail("0002", e.getMessage());
        }
    }

    /** AG3：注册命名空间 schema */
    @PostMapping("/schemas")
    public Response<Map<String, Object>> registerSchema(@RequestParam String namespace,
            @RequestBody String schemaJson) {
        try {
            schemaGate.register(namespace.trim(), schemaJson);
            publishChange("SCHEMA_REGISTERED", namespace, "-", "unknown");
            return Response.success(Map.of("namespace", namespace, "registered", true));
        } catch (IllegalArgumentException e) {
            return Response.fail("0002", e.getMessage());
        }
    }

    /** AG3：查命名空间 schema */
    @GetMapping("/schemas")
    public Response<Map<String, Object>> getSchema(@RequestParam String namespace) {
        String schema = schemaGate.schemaOf(namespace);
        if (schema == null) {
            return Response.success(Map.of("namespace", namespace, "registered", false));
        }
        return Response.success(Map.of("namespace", namespace, "registered", true, "schema", schema));
    }

    /** AG5：长轮询监听（digests=ns→md5；timeoutMs 默认 25s，监听开关关时立即比对返回） */
    @PostMapping("/listen")
    public Response<Map<String, Object>> listen(@RequestBody Map<String, String> digests,
            @RequestParam(defaultValue = "25000") long timeoutMs) {
        long effective = listenerEnabled ? timeoutMs : 0;
        ConfigListenerHub.ListenResult result = listenerHub.listen(digests == null ? Map.of() : digests, effective);
        Map<String, Object> out = new HashMap<>();
        out.put("changed", result.changedNamespaces());
        out.put("timedOut", result.timedOut());
        return Response.success(out);
    }

    /** AG6：设置灰度规则 */
    @PostMapping("/gray")
    public Response<Map<String, Object>> setGrayRule(@RequestBody GrayRule rule) {
        try {
            GrayRule saved = grayRouter.upsert(rule);
            publishChange("GRAY_RULE", rule.namespace(), "-", "unknown");
            return Response.success(toMap(saved));
        } catch (IllegalArgumentException e) {
            return Response.fail("0002", e.getMessage());
        }
    }

    /** AG6：按租户解析生效版本 */
    @PostMapping("/gray/resolve")
    public Response<Map<String, Object>> resolveGray(@RequestParam String namespace,
            @RequestParam String configKey, @RequestParam(required = false) String tenant) {
        ConfigSnapshot stable = snapshotService.current(namespace, configKey);
        if (stable == null) {
            return Response.fail("0002", "配置不存在: " + namespace + "/" + configKey);
        }
        Resolved resolved = grayRouter.resolve(namespace, tenant, stable.version());
        return Response.success(Map.of("namespace", namespace, "configKey", configKey,
                "version", resolved.version(), "by", resolved.by(), "mode", resolved.ruleMode()));
    }

    /** AG6：灰度轨迹 */
    @GetMapping("/gray/trail")
    public Response<List<String>> grayTrail() {
        return Response.success(grayRouter.trail());
    }

    /** AG7：命名空间健康四态 */
    @GetMapping("/health")
    public Response<Map<String, Object>> health(@RequestParam(required = false) String namespace) {
        if (namespace != null && !namespace.isBlank()) {
            return Response.success(Map.of("namespace", namespace, "status", healthService.statusOf(namespace)));
        }
        return Response.success(Map.copyOf(healthService.allStatuses()));
    }

    /** AG7：健康汇总 */
    @GetMapping("/health/summary")
    public Response<HealthSummary> healthSummary() {
        return Response.success(healthService.summary());
    }

    /** AG8：运行漂移检测（期望=当前快照，实际=运行时端口） */
    @PostMapping("/drift/run")
    public Response<Map<String, Object>> runDrift(@RequestParam String namespace,
            @RequestParam String configKey) {
        ConfigSnapshot snapshot = snapshotService.current(namespace, configKey);
        if (snapshot == null) {
            return Response.fail("0002", "配置不存在: " + namespace + "/" + configKey);
        }
        DriftReport report = driftDetector.run(namespace, snapshot.content(), System.currentTimeMillis());
        healthService.update(namespace, new HealthInputs(false, false, report.drifted(), isSuspended(namespace)));
        return Response.success(toMap(report));
    }

    /** AG8：漂移报告查询（可选按命名空间过滤） */
    @GetMapping("/drift/reports")
    public Response<List<Map<String, Object>>> driftReports(@RequestParam(required = false) String namespace) {
        return Response.success(driftDetector.listReports(namespace).stream()
                .map(ConfigCenterController::toMap).collect(Collectors.toList()));
    }

    /** AG9：导出命名空间 bundle（敏感项密文出站） */
    @GetMapping("/bundle/export")
    public Response<Map<String, Object>> exportBundle(@RequestParam String namespace) {
        ConfigBundleCodec.ConfigBundle bundle = facade.exportBundle(namespace);
        Map<String, Object> out = new HashMap<>();
        out.put("namespace", bundle.namespace());
        out.put("entries", bundle.entries());
        out.put("checksum", bundle.checksum());
        out.put("json", ConfigBundleCodec.toJson(bundle));
        return Response.success(out);
    }

    /** AG9：导入 bundle（校验和 + schema 门 + 冲突策略） */
    @PostMapping("/bundle/import")
    public Response<Map<String, Object>> importBundle(@RequestBody String bundleJson,
            @RequestParam(defaultValue = "skip") String conflictPolicy) {
        try {
            ConfigBundleCodec.ImportReport report = facade.importBundle(bundleJson, conflictPolicy);
            if (!report.success()) {
                return Response.fail("0002", String.join("; ", report.errors()));
            }
            publishChange("BUNDLE_IMPORTED", "-", "-", "bundle-import");
            return Response.success(Map.of(
                    "added", report.added(), "overwritten", report.overwritten(),
                    "skipped", report.skipped()));
        } catch (IllegalArgumentException e) {
            return Response.fail("0002", e.getMessage());
        }
    }

    private boolean isSuspended(String namespace) {
        GrayRule rule = grayRouter.ruleOf(namespace);
        return rule != null && rule.suspended();
    }

    private void publishChange(String action, String namespace, String configKey, String operator) {
        try {
            eventPublisher.publish("CONFIG_CENTER_CHANGE", Map.of(
                    "action", action,
                    "namespace", namespace == null ? "-" : namespace,
                    "configKey", configKey == null ? "-" : configKey,
                    "operator", operator == null || operator.isBlank() ? "unknown" : operator));
        } catch (Exception ignored) {
            // 事件尽力而为
        }
    }

    private static Map<String, Object> toMap(ConfigSnapshot s) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", s.id());
        map.put("namespace", s.namespace());
        map.put("configKey", s.configKey());
        map.put("version", s.version());
        map.put("contentMd5", s.contentMd5());
        map.put("sensitive", s.sensitive());
        map.put("publisher", s.publisher());
        map.put("note", s.note());
        map.put("status", s.status());
        return map;
    }

    private static Map<String, Object> toMap(GrayRule rule) {
        Map<String, Object> map = new HashMap<>();
        map.put("namespace", rule.namespace());
        map.put("mode", rule.mode());
        map.put("tenantWhitelist", rule.tenantWhitelist());
        map.put("percentage", rule.percentage());
        map.put("grayVersion", rule.grayVersion());
        map.put("suspended", rule.suspended());
        return map;
    }

    private static Map<String, Object> toMap(DriftReport report) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", report.id());
        map.put("namespace", report.namespace());
        map.put("drifted", report.drifted());
        map.put("missing", report.missing());
        map.put("tampered", report.tampered());
        map.put("extra", report.extra());
        map.put("rows", report.rows());
        return map;
    }
}

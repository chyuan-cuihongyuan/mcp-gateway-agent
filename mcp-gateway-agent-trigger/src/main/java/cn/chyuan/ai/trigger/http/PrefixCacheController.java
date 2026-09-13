package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.governance.adapter.IGovernanceEventPublisher;
import cn.chyuan.ai.domain.llmcache.service.CacheMetricsCollector;
import cn.chyuan.ai.domain.llmcache.service.PrefixCacheInterceptor;
import cn.chyuan.ai.domain.llmcache.service.PrefixCacheStore;
import cn.chyuan.ai.domain.modelcatalog.service.ModelCatalogService;
import cn.chyuan.ai.domain.modelcatalog.service.ModelCatalogService.ModelEntry;
import cn.chyuan.ai.domain.modelcatalog.service.ModelHealthProbe;
import cn.chyuan.ai.domain.modelcatalog.service.ModelHealthProbe.ProbeRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
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

/**
 * 前缀缓存与模型目录管理端点（六期 AJ 簇 0277-0284）—
 * 缓存统计/树快照/清空（AJ8/AJ3）、指标快照（AJ7）、模型目录 CRUD（AJ5）、
 * 模型探活与状态（AJ6）。管理操作经 CACHE_MANAGE 事件留痕。
 */
@Slf4j
@RestController
@CrossOrigin(origins = {"http://localhost:3000"})
@RequestMapping("/admin/v1/prefix-cache")
public class PrefixCacheController {

    private final PrefixCacheInterceptor interceptor;
    private final PrefixCacheStore store;
    private final CacheMetricsCollector metrics;
    private final ModelCatalogService catalogService;
    private final ModelHealthProbe healthProbe;
    private final IGovernanceEventPublisher eventPublisher;

    public PrefixCacheController(PrefixCacheInterceptor interceptor, PrefixCacheStore store,
            CacheMetricsCollector metrics, ModelCatalogService catalogService,
            ModelHealthProbe healthProbe, IGovernanceEventPublisher eventPublisher) {
        this.interceptor = interceptor;
        this.store = store;
        this.metrics = metrics;
        this.catalogService = catalogService;
        this.healthProbe = healthProbe;
        this.eventPublisher = eventPublisher;
    }

    /** AJ8：缓存统计（容量占用/租户分布/收益汇总） */
    @GetMapping("/stats")
    public Response<Map<String, Object>> stats() {
        Map<String, Object> out = new HashMap<>();
        out.put("store", store.stats());
        out.put("tree", interceptor.treeSnapshot());
        out.put("metrics", metrics.snapshot());
        out.put("enabled", interceptor.isEnabled());
        return Response.success(out);
    }

    /** AJ8：按租户清空（审计留痕） */
    @DeleteMapping("/tenants/{tenant}")
    public Response<Map<String, Object>> clearTenant(@PathVariable String tenant) {
        int removed = interceptor.clearTenant(tenant);
        publishChange("CACHE_CLEARED", "tenant=" + tenant);
        return Response.success(Map.of("tenant", tenant, "removed", removed));
    }

    /** AJ8：全量清空（审计留痕） */
    @DeleteMapping
    public Response<Map<String, Object>> clearAll() {
        int removed = interceptor.clearAll();
        publishChange("CACHE_CLEARED", "all");
        return Response.success(Map.of("removed", removed));
    }

    /** AJ5：注册模型目录条目（计价联动校验，缺失警告不阻断） */
    @PostMapping("/models")
    public Response<Map<String, Object>> registerModel(@RequestBody ModelEntry entry) {
        try {
            ModelEntry saved = catalogService.register(entry);
            ModelCatalogService.PricingLinkage linkage = catalogService.checkPricing(saved);
            publishChange("MODEL_REGISTERED", saved.model());
            Map<String, Object> out = new HashMap<>(toMap(saved));
            out.put("pricingLinked", linkage.linked());
            out.put("pricingWarned", linkage.warned());
            return Response.success(out);
        } catch (IllegalArgumentException e) {
            return Response.fail("0002", e.getMessage());
        }
    }

    /** AJ5：模型清单 */
    @GetMapping("/models")
    public Response<List<Map<String, Object>>> listModels() {
        return Response.success(catalogService.listAll().stream()
                .map(PrefixCacheController::toMap).toList());
    }

    /** AJ5：context 超限预检 */
    @PostMapping("/models/context-check")
    public Response<Map<String, Object>> contextCheck(@RequestParam String model,
            @RequestParam int estimatedTokens) {
        boolean pass = catalogService.assertContextWithinLimit(model, estimatedTokens);
        return Response.success(Map.of("model", model, "estimatedTokens", estimatedTokens, "pass", pass));
    }

    /** AJ6：手动触发探活 */
    @PostMapping("/models/{model}/probe")
    public Response<ProbeRecord> probe(@PathVariable String model) {
        return Response.success(healthProbe.runProbe(model, System.currentTimeMillis()));
    }

    /** AJ6：模型状态（就绪聚合，可传逗号分隔模型清单） */
    @GetMapping("/models/status")
    public Response<Map<String, String>> status(@RequestParam(required = false) String models) {
        List<String> list = models == null || models.isBlank()
                ? catalogService.listAll().stream().map(ModelEntry::model).toList()
                : List.of(models.split(","));
        return Response.success(healthProbe.statusAll(list));
    }

    /** AJ6：探活历史 */
    @GetMapping("/models/{model}/history")
    public Response<List<ProbeRecord>> history(@PathVariable String model,
            @RequestParam(defaultValue = "20") int limit) {
        return Response.success(healthProbe.history(model, limit));
    }

    private void publishChange(String action, String detail) {
        try {
            eventPublisher.publish("CACHE_MANAGE", Map.of("action", action, "detail", detail));
        } catch (Exception ignored) {
            // 事件尽力而为
        }
    }

    private static Map<String, Object> toMap(ModelEntry entry) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", entry.id());
        map.put("model", entry.model());
        map.put("contextLimit", entry.contextLimit());
        map.put("modalities", entry.modalities());
        map.put("pricingEntryId", entry.pricingEntryId());
        map.put("status", entry.status());
        return map;
    }
}

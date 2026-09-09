package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.domain.llmchannel.adapter.port.ILlmResponseCachePort;
import cn.chyuan.ai.domain.usage.adapter.repository.IUsageRepository;
import cn.chyuan.ai.api.response.Response;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 缓存管理接口（工单 0100：/admin/v1/cache）
 *
 * <p>统计（命中率为账本 cache_hit 口径——含 0097 前流量的分母）、按键删除、前缀清空；
 * 写操作挂审计。角色语义由 AdminJwtAuthFilter 矩阵承载（读=READONLY 起、写=ADMIN 起）。
 *
 * @author chyuan
 */
@Slf4j
@RestController
@RequestMapping("/admin/v1/cache")
public class AdminCacheController {

    @Resource
    private ObjectProvider<ILlmResponseCachePort> responseCacheProvider;

    @Resource
    private IUsageRepository usageRepository;

    @Resource
    private cn.chyuan.ai.domain.governance.service.IAuditService auditService;

    /** 统计：Redis 键数/TTL/降级标识 + 账本口径命中率 */
    @GetMapping("/stats")
    public Response<Map<String, Object>> stats(
            @RequestParam(required = false, defaultValue = "") String fromDate,
            @RequestParam(required = false, defaultValue = "") String toDate) {
        Map<String, Object> result = new LinkedHashMap<>();
        ILlmResponseCachePort cache = responseCacheProvider.getIfAvailable();
        if (cache == null) {
            result.put("available", false);
            result.put("degraded", true);
        } else {
            result.putAll(cache.stats());
            result.put("degraded", !cache.available());
        }
        Map<String, Object> hitStats = usageRepository.cacheHitStats(
                blankToNull(fromDate), blankToNull(toDate));
        long total = hitStats.get("total") instanceof Number n ? n.longValue() : 0;
        long hits = hitStats.get("hits") instanceof Number n ? n.longValue() : 0;
        result.put("llmRequests", total);
        result.put("cacheHits", hits);
        result.put("hitRatePercent", total == 0 ? 0.0 : Math.round(hits * 1000.0 / total) / 10.0);
        return Response.success(result);
    }

    /** 按键删除（key 形如 mcp.gateway.llm.cache:<sha256>） */
    @DeleteMapping("/keys/{key}")
    public Response<Boolean> deleteKey(@PathVariable String key) {
        ILlmResponseCachePort cache = requireCache();
        boolean deleted = cache.delete(key);
        audit("CACHE_KEY_DELETE", key, "deleted=" + deleted);
        return Response.success(deleted);
    }

    /** 前缀清空（返回删除数；-1=缓存不可用） */
    @DeleteMapping
    public Response<Long> purge() {
        ILlmResponseCachePort cache = requireCache();
        long removed = cache.purge();
        audit("CACHE_PURGE", "mcp.gateway.llm.cache:*", "removed=" + removed);
        return Response.success(removed);
    }

    private ILlmResponseCachePort requireCache() {
        ILlmResponseCachePort cache = responseCacheProvider.getIfAvailable();
        if (cache == null) {
            throw new cn.chyuan.ai.types.exception.AppException(
                    cn.chyuan.ai.types.enums.McpErrorCodes.INVALID_PARAMS, "缓存未装配");
        }
        return cache;
    }

    private void audit(String action, String resourceId, String detail) {
        try {
            auditService.record(cn.chyuan.ai.domain.governance.model.entity.AuditCommandEntity.builder()
                    .actor("admin")
                    .action(action)
                    .resourceType("LLM_CACHE")
                    .resourceId(resourceId)
                    .afterJson(detail)
                    .build());
        } catch (Exception ignored) {
            // 审计尽力而为
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

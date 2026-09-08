package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.domain.externalattach.adapter.repository.IExternalAttachRepository;
import cn.chyuan.ai.domain.externalattach.model.valobj.ExternalAttachVO;
import cn.chyuan.ai.domain.session.adapter.repository.ISessionRouteRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 健康聚合端点（工单 0068，Kong status 口径）
 *
 * <p>匿名可达（无凭证信息与渠道敏感字段——渠道仅状态计数）：
 * db（任一仓储读通）/ redis（路由表读，未装配视为 unknown）/ channels（三态计数）。
 * 整体 UP（全通）/ DEGRADED（任一非致命组件故障；DB 故障记 DOWN——就绪判定归调用方）。
 *
 * @author chyuan
 */
@Slf4j
@RestController
public class HealthController {

    private final IExternalAttachRepository attachRepository;
    private final ISessionRouteRepository sessionRouteRepository;

    public HealthController(ObjectProvider<IExternalAttachRepository> attachRepositoryProvider,
            ObjectProvider<ISessionRouteRepository> sessionRouteRepositoryProvider) {
        this.attachRepository = attachRepositoryProvider.getIfAvailable();
        this.sessionRouteRepository = sessionRouteRepositoryProvider.getIfAvailable();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> components = new LinkedHashMap<>();

        String dbStatus = "UP";
        int enabled = 0;
        int autoDisabled = 0;
        int manualDisabled = 0;
        try {
            if (attachRepository != null) {
                List<ExternalAttachVO> attaches = attachRepository.findAllAttaches();
                for (ExternalAttachVO attach : attaches) {
                    if (attach.getStatus() == null) {
                        continue;
                    }
                    switch (attach.getStatus()) {
                        case ExternalAttachVO.STATUS_ENABLED -> enabled++;
                        case ExternalAttachVO.STATUS_AUTO_DISABLED -> autoDisabled++;
                        case ExternalAttachVO.STATUS_MANUAL_DISABLED -> manualDisabled++;
                        default -> { }
                    }
                }
            }
        } catch (Exception e) {
            dbStatus = "DOWN";
            log.warn("健康检查 DB 组件异常：{}", e.getMessage());
        }
        components.put("db", Map.of("status", dbStatus));
        components.put("channels", Map.of(
                "enabled", enabled, "autoDisabled", autoDisabled, "manualDisabled", manualDisabled));

        String redisStatus = "UNKNOWN";
        if (sessionRouteRepository != null) {
            try {
                sessionRouteRepository.findInstance("health-probe");
                redisStatus = "UP";
            } catch (Exception e) {
                redisStatus = "DOWN";
                log.debug("健康检查 Redis 组件异常：{}", e.getMessage());
            }
        }
        components.put("redis", Map.of("status", redisStatus));

        String overall = "DOWN".equals(dbStatus) ? "DOWN"
                : ("DOWN".equals(redisStatus) ? "DEGRADED" : "UP");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", overall);
        body.put("components", components);
        body.put("checkedAt", System.currentTimeMillis());
        return body;
    }
}

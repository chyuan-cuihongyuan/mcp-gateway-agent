package cn.chyuan.ai.domain.session.service;

import cn.chyuan.ai.domain.session.adapter.repository.ISessionRouteRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 会话亲和服务（工单 0055，agentgateway MCP-Session-ID 粘性口径）
 *
 * <p>会话建立登记归属实例（路由表 TTL 与会话超时一致）、终止移除；
 * 请求到达时比对归属与本地实例，不一致计漂移指标（gateway.session.drift）
 * 供负载均衡器校验亲和配置；全部操作失败静默（无路由表不影响单实例语义）。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class SessionAffinityService {

    /** 响应头：本实例标识（负载均衡器/运维校验亲和用） */
    public static final String INSTANCE_HEADER = "X-Gateway-Instance";

    @Autowired(required = false)
    private ISessionRouteRepository routeRepository;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    /** 本实例标识（可显式配置；默认 主机名-随机后缀，多实例部署建议显式配置） */
    @Value("${governance.instance-id:}")
    private String configuredInstanceId;

    private volatile String resolvedInstanceId;

    public String instanceId() {
        if (resolvedInstanceId == null) {
            resolvedInstanceId = configuredInstanceId != null && !configuredInstanceId.isBlank()
                    ? configuredInstanceId
                    : defaultInstanceId();
        }
        return resolvedInstanceId;
    }

    private static String defaultInstanceId() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "local";
        }
        return host + "-" + Integer.toHexString(new java.security.SecureRandom().nextInt(0xFFFF));
    }

    /** 会话建立：登记归属 */
    public void register(String sessionId, Duration ttl) {
        if (routeRepository == null || sessionId == null) {
            return;
        }
        try {
            routeRepository.save(sessionId, instanceId(), ttl);
        } catch (Exception e) {
            log.debug("会话路由登记失败（无路由表不影响本地语义）sessionId={}", sessionId);
        }
    }

    /** 会话终止：移除路由 */
    public void release(String sessionId) {
        if (routeRepository == null || sessionId == null) {
            return;
        }
        try {
            routeRepository.delete(sessionId);
        } catch (Exception e) {
            log.debug("会话路由移除失败 sessionId={}", sessionId);
        }
    }

    /**
     * 请求到达：漂移检测（归属实例非本机即漂移）。
     *
     * @return true=会话漂移到非本实例（负载均衡亲和失效信号）
     */
    public boolean checkDrift(String sessionId) {
        if (routeRepository == null || sessionId == null) {
            return false;
        }
        try {
            String owner = routeRepository.findInstance(sessionId);
            if (owner != null && !owner.equals(instanceId())) {
                if (meterRegistry != null) {
                    meterRegistry.counter("gateway.session.drift", "gateway", "").increment();
                }
                return true;
            }
        } catch (Exception e) {
            log.debug("会话路由查询失败 sessionId={}", sessionId);
        }
        return false;
    }
}

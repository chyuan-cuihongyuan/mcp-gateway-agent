package cn.chyuan.ai.domain.session.adapter.repository;

import java.time.Duration;

/**
 * 会话亲和路由表端口（工单 0055：sessionId → 实例标识，多实例粘性基础）
 *
 * <p>与本地会话表同生命周期（TTL 同步过期）；Redis 不可用时静默降级
 * （无路由表不影响单实例语义）。
 *
 * @author chyuan
 */
public interface ISessionRouteRepository {

    /** 登记/续期会话归属实例（TTL 与会话超时一致） */
    void save(String sessionId, String instanceId, Duration ttl);

    /** 查询会话归属实例（未登记返回 null） */
    String findInstance(String sessionId);

    /** 会话终止时移除路由 */
    void delete(String sessionId);
}

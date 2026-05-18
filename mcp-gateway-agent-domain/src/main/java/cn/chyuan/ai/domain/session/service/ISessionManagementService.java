package cn.chyuan.ai.domain.session.service;

import cn.chyuan.ai.domain.session.model.valobj.SessionConfigVO;

/**
 * 会话管理服务接口
 *
 * @author chyuan
 *         2025/12/2 07:51
 */
public interface ISessionManagementService {

    /**
     * 创建会话
     * 
     * @return 会话配置
     */
    SessionConfigVO createSession(String gatewayId, String apiKey);

    /**
     * 删除会话
     * 
     * @param sessionId 会话ID
     */
    void removeSession(String sessionId);

    /**
     * 获取会话
     * 
     * @param sessionId 会话ID
     * @return 会话配置
     */
    SessionConfigVO getSession(String sessionId);

    /**
     * 清理过期会话
     */
    void cleanupExpiredSessions();

    /**
     * 关闭服务时，清理资源使用
     */
    void shutdown();
}

package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.model.entity.LoginCommandEntity;

/**
 * admin 登录服务（工单 0017）
 *
 * @author chyuan
 */
public interface IAdminAuthService {

    /** 登录结果：JWT token + 角色 */
    record LoginResult(String token, String username, String role) {
    }

    /**
     * 登录：BCrypt 校验 → 签发 JWT（默认 2h）
     *
     * @throws AppException(INSUFFICIENT_PERMISSIONS) 用户名或密码错误
     */
    LoginResult login(LoginCommandEntity command);
}

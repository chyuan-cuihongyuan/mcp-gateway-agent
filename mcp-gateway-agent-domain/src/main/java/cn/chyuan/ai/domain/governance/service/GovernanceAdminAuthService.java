package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.adapter.codec.IJwtCodec;
import cn.chyuan.ai.domain.governance.adapter.codec.IPasswordCodec;
import cn.chyuan.ai.domain.governance.adapter.repository.IAdminUserRepository;
import cn.chyuan.ai.domain.governance.model.entity.AuditCommandEntity;
import cn.chyuan.ai.domain.governance.model.entity.LoginCommandEntity;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import cn.chyuan.ai.types.exception.AppException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * admin 登录服务（工单 0017；类名 GovernanceAdminAuthService 以避开 case 层同名 Bean）
 *
 * @author chyuan
 */
@Slf4j
@Service
public class GovernanceAdminAuthService implements IAdminAuthService {

    @Resource
    private IAdminUserRepository adminUserRepository;

    @Resource
    private IPasswordCodec passwordCodec;

    @Resource
    private IJwtCodec jwtCodec;

    @Resource
    private IAuditService auditService;

    /** 登录 JWT 有效期（秒），默认 2 小时 */
    @Value("${governance.jwt.ttl-seconds:7200}")
    private long ttlSeconds;

    @Override
    public LoginResult login(LoginCommandEntity command) {
        IAdminUserRepository.AdminUserVO user = adminUserRepository.findByUsername(command.getUsername());
        if (user == null || !passwordCodec.matches(command.getPassword(), user.getPasswordHash())) {
            throw new AppException(McpErrorCodes.INSUFFICIENT_PERMISSIONS, "用户名或密码错误");
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new AppException(McpErrorCodes.INSUFFICIENT_PERMISSIONS, "用户已停用");
        }

        String token = jwtCodec.issue(user.getUsername(), List.of(user.getRole()), ttlSeconds);

        auditService.record(AuditCommandEntity.builder()
                .actor(user.getUsername())
                .action("LOGIN")
                .resourceType("ADMIN_USER")
                .resourceId(user.getUsername())
                .build());

        return new LoginResult(token, user.getUsername(), user.getRole());
    }
}

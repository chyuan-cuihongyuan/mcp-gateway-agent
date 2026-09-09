package cn.chyuan.ai.cases.admin.manage;

import cn.chyuan.ai.domain.governance.adapter.codec.IPasswordCodec;
import cn.chyuan.ai.domain.governance.adapter.repository.IAdminUserRepository;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import cn.chyuan.ai.types.exception.AppException;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 管理员账户管理用例（工单 0110，SUPER_ADMIN 专属——矩阵由 AdminJwtAuthFilter 承载）
 *
 * <p>结构性防锁：不可删除/停用自己；删除或降级后必须仍存在至少一个启用的 SUPER_ADMIN。
 * 全操作审计（SECURITY 型）。
 *
 * @author chyuan
 */
@Service
public class AdminUserService {

    private static final Set<String> ROLES = Set.of("SUPER_ADMIN", "ADMIN", "AUDITOR", "READONLY");

    @Resource
    private IAdminUserRepository repository;

    @Resource
    private IPasswordCodec passwordCodec;

    @Resource
    private cn.chyuan.ai.domain.governance.service.IAuditService auditService;

    public List<IAdminUserRepository.AdminUserVO> list() {
        // 密码哈希不出库
        return repository.findAll().stream()
                .map(vo -> vo.toBuilder().passwordHash(null).build())
                .toList();
    }

    public IAdminUserRepository.AdminUserVO create(String operator, String username, String password, String role) {
        validateRole(role);
        if (StringUtils.isBlank(username) || StringUtils.isBlank(password) || password.length() < 8) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "用户名不能为空且密码至少 8 位");
        }
        if (repository.findByUsername(username) != null) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "用户名已存在: " + username);
        }
        repository.insert(username, passwordCodec.encode(password), role);
        audit(operator, "CREATE_USER", username, "role=" + role);
        IAdminUserRepository.AdminUserVO created = repository.findByUsername(username);
        return created == null ? null : created.toBuilder().passwordHash(null).build();
    }

    public void updateRole(String operator, String username, String role) {
        validateRole(role);
        require(username);
        if ("SUPER_ADMIN".equals(roleOf(username)) && !"SUPER_ADMIN".equals(role)
                && countEnabledSuperAdmins() <= 1) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "不能降级最后一个超级管理员");
        }
        repository.updateRole(username, role);
        audit(operator, "UPDATE_USER_ROLE", username, "role=" + role);
    }

    public void resetPassword(String operator, String username, String newPassword) {
        require(username);
        if (StringUtils.isBlank(newPassword) || newPassword.length() < 8) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "密码至少 8 位");
        }
        repository.updatePassword(username, passwordCodec.encode(newPassword));
        audit(operator, "RESET_USER_PASSWORD", username, null);
    }

    public void updateStatus(String operator, String username, String status) {
        require(username);
        if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "非法状态: " + status);
        }
        if ("DISABLED".equals(status) && username.equals(operator)) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "不能停用当前登录账户");
        }
        if ("DISABLED".equals(status) && "SUPER_ADMIN".equals(roleOf(username))
                && countEnabledSuperAdmins() <= 1) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "不能停用最后一个超级管理员");
        }
        repository.updateStatus(username, status);
        audit(operator, "UPDATE_USER_STATUS", username, "status=" + status);
    }

    public void delete(String operator, String username) {
        require(username);
        if (username.equals(operator)) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "不能删除当前登录账户");
        }
        if ("SUPER_ADMIN".equals(roleOf(username)) && countEnabledSuperAdmins() <= 1) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "不能删除最后一个超级管理员");
        }
        repository.deleteByUsername(username);
        audit(operator, "DELETE_USER", username, null);
    }

    // ---- 内部 ----

    private void require(String username) {
        if (repository.findByUsername(username) == null) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "用户不存在: " + username);
        }
    }

    private String roleOf(String username) {
        IAdminUserRepository.AdminUserVO vo = repository.findByUsername(username);
        return vo == null ? null : vo.getRole();
    }

    private long countEnabledSuperAdmins() {
        return repository.findAll().stream()
                .filter(vo -> "SUPER_ADMIN".equals(vo.getRole()) && "ACTIVE".equals(vo.getStatus()))
                .count();
    }

    private void validateRole(String role) {
        if (role == null || !ROLES.contains(role)) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "非法角色: " + role);
        }
    }

    private void audit(String operator, String action, String username, String detail) {
        try {
            auditService.record(cn.chyuan.ai.domain.governance.model.entity.AuditCommandEntity.builder()
                    .actor(operator).action(action).resourceType("ADMIN_USER")
                    .resourceId(username).afterJson(detail).type("SECURITY").build());
        } catch (Exception ignored) {
            // 审计尽力而为
        }
    }
}

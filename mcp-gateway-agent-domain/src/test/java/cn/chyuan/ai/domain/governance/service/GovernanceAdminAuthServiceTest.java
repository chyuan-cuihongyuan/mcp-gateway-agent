package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.adapter.codec.IJwtCodec;
import cn.chyuan.ai.domain.governance.adapter.codec.IPasswordCodec;
import cn.chyuan.ai.domain.governance.adapter.repository.IAdminUserRepository;
import cn.chyuan.ai.domain.governance.model.entity.AuditCommandEntity;
import cn.chyuan.ai.domain.governance.model.entity.LoginCommandEntity;
import cn.chyuan.ai.types.exception.AppException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * admin 登录服务测试（工单 0017 验收：BCrypt 校验 → 签发带角色的 JWT → LOGIN 审计）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("admin 登录服务测试")
public class GovernanceAdminAuthServiceTest {

    @Mock
    private IAdminUserRepository adminUserRepository;

    @Mock
    private IPasswordCodec passwordCodec;

    @Mock
    private IJwtCodec jwtCodec;

    @Mock
    private IAuditService auditService;

    @InjectMocks
    private GovernanceAdminAuthService service;

    private IAdminUserRepository.AdminUserVO activeUser(String role) {
        return IAdminUserRepository.AdminUserVO.builder()
                .id(1L)
                .username("admin")
                .passwordHash("$2a$10$encoded")
                .role(role)
                .status("ACTIVE")
                .build();
    }

    @Test
    @DisplayName("登录成功 — 签发带角色的 JWT 并写 LOGIN 审计")
    public void testLogin_Success_IssuesJwtAndAudits() {
        // 准备
        when(adminUserRepository.findByUsername("admin")).thenReturn(activeUser("ADMIN"));
        when(passwordCodec.matches("correct-password", "$2a$10$encoded")).thenReturn(true);
        when(jwtCodec.issue(eq("admin"), eq(List.of("ADMIN")), anyLong())).thenReturn("token-1");

        // 执行
        IAdminAuthService.LoginResult result = service.login(
                LoginCommandEntity.builder().username("admin").password("correct-password").build());

        // 验证
        assertEquals("token-1", result.token());
        assertEquals("admin", result.username());
        assertEquals("ADMIN", result.role());

        ArgumentCaptor<AuditCommandEntity> auditCaptor = ArgumentCaptor.forClass(AuditCommandEntity.class);
        verify(auditService).record(auditCaptor.capture());
        assertEquals("LOGIN", auditCaptor.getValue().getAction());
        assertEquals("admin", auditCaptor.getValue().getActor());
    }

    @Test
    @DisplayName("密码错误 — 拒绝且不签发、不审计")
    public void testLogin_WrongPassword_Rejected() {
        when(adminUserRepository.findByUsername("admin")).thenReturn(activeUser("ADMIN"));
        when(passwordCodec.matches("wrong-password", "$2a$10$encoded")).thenReturn(false);

        AppException ex = assertThrows(AppException.class, () -> service.login(
                LoginCommandEntity.builder().username("admin").password("wrong-password").build()));

        assertEquals("用户名或密码错误", ex.getInfo());
        verify(jwtCodec, never()).issue(anyString(), anyList(), anyLong());
        verify(auditService, never()).record(any(AuditCommandEntity.class));
    }

    @Test
    @DisplayName("用户不存在 — 统一按凭证错误拒绝")
    public void testLogin_UserNotFound_Rejected() {
        when(adminUserRepository.findByUsername("nobody")).thenReturn(null);

        AppException ex = assertThrows(AppException.class, () -> service.login(
                LoginCommandEntity.builder().username("nobody").password("whatever").build()));

        assertEquals("用户名或密码错误", ex.getInfo());
        verifyNoInteractions(jwtCodec);
        verify(auditService, never()).record(any(AuditCommandEntity.class));
    }

    @Test
    @DisplayName("用户已停用 — 拒绝登录")
    public void testLogin_DisabledUser_Rejected() {
        IAdminUserRepository.AdminUserVO disabled = activeUser("ADMIN");
        disabled.setStatus("DISABLED");
        when(adminUserRepository.findByUsername("admin")).thenReturn(disabled);
        when(passwordCodec.matches("correct-password", "$2a$10$encoded")).thenReturn(true);

        AppException ex = assertThrows(AppException.class, () -> service.login(
                LoginCommandEntity.builder().username("admin").password("correct-password").build()));

        assertEquals("用户已停用", ex.getInfo());
        verify(jwtCodec, never()).issue(anyString(), anyList(), anyLong());
    }
}

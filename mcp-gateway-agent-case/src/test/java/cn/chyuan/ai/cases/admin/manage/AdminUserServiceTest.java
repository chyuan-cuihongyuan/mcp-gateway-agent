package cn.chyuan.ai.cases.admin.manage;

import cn.chyuan.ai.domain.governance.adapter.codec.IPasswordCodec;
import cn.chyuan.ai.domain.governance.adapter.repository.IAdminUserRepository;
import cn.chyuan.ai.domain.governance.service.IAuditService;
import cn.chyuan.ai.types.exception.AppException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * 管理员账户管理测试（工单 0110：防锁约束/角色校验/审计）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("管理员账户管理测试")
class AdminUserServiceTest {

    @Mock
    private IAdminUserRepository repository;

    @Mock
    private IPasswordCodec passwordCodec;

    @Mock
    private IAuditService auditService;

    @InjectMocks
    private AdminUserService service;

    private IAdminUserRepository.AdminUserVO user(String name, String role, String status) {
        return IAdminUserRepository.AdminUserVO.builder()
                .username(name).role(role).status(status).build();
    }

    @BeforeEach
    void setUp() {
        Mockito.lenient().when(repository.findAll()).thenReturn(List.of(
                user("root", "SUPER_ADMIN", "ACTIVE"),
                user("alice", "ADMIN", "ACTIVE")));
    }

    @Test
    @DisplayName("防锁：删自己/删最后超管/停用最后超管被拒")
    void antiLock() {
        Assertions.assertThrows(AppException.class, () -> service.delete("root", "root"));
        Assertions.assertThrows(AppException.class, () -> service.delete("alice", "root"));
        Assertions.assertThrows(AppException.class, () -> service.updateStatus("alice", "root", "DISABLED"));
        Assertions.assertThrows(AppException.class, () -> service.updateRole("alice", "root", "ADMIN"));
        Mockito.verify(repository, never()).deleteByUsername(anyString());
    }

    @Test
    @DisplayName("创建：角色校验/重名拒绝/密码最短 8 位；成功审计")
    void createValidation() {
        Assertions.assertThrows(AppException.class, () -> service.create("root", "bob", "short", "ADMIN"));
        when(repository.findByUsername("alice")).thenReturn(user("alice", "ADMIN", "ACTIVE"));
        Assertions.assertThrows(AppException.class, () -> service.create("root", "alice", "longenough", "ADMIN"));
        Assertions.assertThrows(AppException.class, () -> service.create("root", "bob", "longenough", "HACKER"));

        when(repository.findByUsername("carol")).thenReturn(null);
        when(passwordCodec.encode(anyString())).thenReturn("bcrypthash");
        service.create("root", "carol", "longenough", "AUDITOR");
        Mockito.verify(repository).insert(eq("carol"), anyString(), eq("AUDITOR"));
        Mockito.verify(auditService).record(Mockito.argThat(cmd ->
                "CREATE_USER".equals(cmd.getAction()) && "SECURITY".equals(cmd.getType())));
    }

    @Test
    @DisplayName("列表：密码哈希不出库")
    void listMasksHash() {
        when(repository.findAll()).thenReturn(List.of(
                IAdminUserRepository.AdminUserVO.builder()
                        .username("root").role("SUPER_ADMIN").status("ACTIVE").passwordHash("bcrypthash").build()));
        var list = service.list();
        Assertions.assertNull(list.get(0).getPasswordHash());
    }
}

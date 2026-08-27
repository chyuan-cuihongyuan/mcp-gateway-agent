package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.adapter.repository.IVirtualKeyRepository;
import cn.chyuan.ai.domain.governance.model.entity.AuditCommandEntity;
import cn.chyuan.ai.domain.governance.model.entity.VirtualKeyCommandEntity;
import cn.chyuan.ai.domain.governance.model.valobj.VirtualKeyVO;
import cn.chyuan.ai.types.util.KeyHashUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 虚拟密钥领域服务测试（工单 0017：创建脱敏/明文一次性/审计/迁移幂等）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("虚拟密钥服务测试")
public class VirtualKeyServiceTest {

    @Mock
    private IVirtualKeyRepository repository;

    @Mock
    private GovernanceAuthService governanceAuthService;

    @Mock
    private IAuditService auditService;

    @InjectMocks
    private VirtualKeyService service;

    @Test
    @DisplayName("创建密钥 — vk- 前缀、明文仅一次返回、哈希入库、写审计")
    public void testCreate_PlaintextOnceAndAudit() {
        // 准备 — insert 回填主键
        when(repository.insert(anyString(), any(VirtualKeyVO.class))).thenAnswer(inv -> {
            VirtualKeyVO vo = inv.getArgument(1);
            vo.setId(42L);
            return vo;
        });

        // 执行
        VirtualKeyVO result = service.create(VirtualKeyCommandEntity.builder().keyName("integration-1").build());

        // 验证 — vk- 凭证形态
        assertNotNull(result.getPlaintextOnce(), "创建响应应返回一次明文");
        assertTrue(result.getPlaintextOnce().startsWith("vk-"), "凭证应为 vk- 前缀");
        assertEquals(3 + 48, result.getPlaintextOnce().length(), "随机段 48 位");
        // 哈希入库（非明文）
        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository).insert(hashCaptor.capture(), any(VirtualKeyVO.class));
        assertEquals(KeyHashUtil.sha256Hex(result.getPlaintextOnce()), hashCaptor.getValue(),
                "入库的应是凭证 SHA-256 哈希而非明文");
        // 审计
        verify(auditService).record(any(AuditCommandEntity.class));
    }

    @Test
    @DisplayName("查询路径 — 不携带明文字段")
    public void testGetById_NoPlaintext() {
        when(repository.findById(42L)).thenReturn(VirtualKeyVO.builder().id(42L).keyName("k").status("ACTIVE").build());

        VirtualKeyVO vo = service.getById(42L);

        assertNull(vo.getPlaintextOnce(), "查询不应返回明文");
    }

    @Test
    @DisplayName("吊销 — 状态置 REVOKED 并失效认证缓存")
    public void testRevoke_InvalidatesCache() {
        when(repository.findById(42L)).thenReturn(VirtualKeyVO.builder().id(42L).keyName("k").status("ACTIVE").build());

        service.revoke(42L);

        verify(repository).updateStatus(42L, "REVOKED");
        verify(governanceAuthService).invalidateAll();
        verify(auditService).record(any(AuditCommandEntity.class));
    }

    @Test
    @DisplayName("存量迁移 — 每小时限次换算 RPM 并幂等跳过")
    public void testMigrateLegacy_ConvertsAndIdempotent() {
        // 准备 — 旧表 2 条：一条待迁移，一条已迁移（同哈希已存在）
        String existingKey = "gw-existing";
        String newKey = "gw-new";
        when(repository.queryLegacyAuthRecords()).thenReturn(List.of(
                new IVirtualKeyRepository.LegacyAuthRecord("gateway_001", existingKey, 120, null, 1),
                new IVirtualKeyRepository.LegacyAuthRecord("gateway_001", newKey, 3600, null, 1)));

        when(repository.migrateLegacy(eq(KeyHashUtil.sha256Hex(existingKey)), anyString(), eq("ACTIVE"), any(), any(), eq("gateway_001")))
                .thenReturn(0); // 已存在 → 跳过
        AtomicReference<Integer> rpmCaptured = new AtomicReference<>();
        when(repository.migrateLegacy(eq(KeyHashUtil.sha256Hex(newKey)), anyString(), eq("ACTIVE"), any(), any(), eq("gateway_001")))
                .thenAnswer(inv -> {
                    rpmCaptured.set(inv.getArgument(3));
                    return 1;
                });

        // 执行
        int migrated = service.migrateLegacyKeys();

        // 验证 — 3600 次/小时 → 60 RPM
        assertEquals(1, migrated, "仅新 key 实际迁移");
        assertEquals(60, rpmCaptured.get(), "每小时限次应换算为 RPM（3600/h → 60/min）");
        verify(repository, times(2)).migrateLegacy(anyString(), anyString(), anyString(), any(), any(), anyString());
    }
}

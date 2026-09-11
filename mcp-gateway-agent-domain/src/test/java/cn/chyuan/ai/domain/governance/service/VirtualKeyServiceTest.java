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

import java.util.Date;
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
    @DisplayName("派生状态四态（0045）— ACTIVE 已过期→EXPIRED；非 ACTIVE 原样；未过期→ACTIVE")
    public void testDerivedStatus() {
        when(repository.findById(1L)).thenReturn(VirtualKeyVO.builder().id(1L).status("ACTIVE")
                .expiresAt(new Date(System.currentTimeMillis() - 1_000)).build());
        when(repository.findById(2L)).thenReturn(VirtualKeyVO.builder().id(2L).status("DISABLED").build());
        when(repository.findById(3L)).thenReturn(VirtualKeyVO.builder().id(3L).status("ACTIVE")
                .expiresAt(new Date(System.currentTimeMillis() + 60_000)).build());
        when(repository.findById(4L)).thenReturn(VirtualKeyVO.builder().id(4L).status("ACTIVE").build());

        assertEquals("EXPIRED", service.getById(1L).getDerivedStatus());
        assertEquals("DISABLED", service.getById(2L).getDerivedStatus());
        assertEquals("ACTIVE", service.getById(3L).getDerivedStatus());
        assertEquals("ACTIVE", service.getById(4L).getDerivedStatus(), "无过期时间=永久 ACTIVE");
    }

    @Test
    @DisplayName("IP 白名单随创建/更新入参传递（0045）")
    public void testIpAllowListCarried() {
        when(repository.insert(anyString(), any(VirtualKeyVO.class))).thenAnswer(inv -> {
            VirtualKeyVO vo = inv.getArgument(1);
            vo.setId(7L);
            return vo;
        });

        VirtualKeyVO result = service.create(VirtualKeyCommandEntity.builder()
                .keyName("with-ip").ipAllowList(java.util.List.of("10.0.0.0/8")).build());

        assertEquals(java.util.List.of("10.0.0.0/8"), result.getIpAllowList());
    }

    @Test
    @DisplayName("模型白名单（0157）— 创建透传并归一（trim）；空白条目拒绝")
    public void testAllowedModelsValidationAndCarried() {
        when(repository.insert(anyString(), any(VirtualKeyVO.class))).thenAnswer(inv -> {
            VirtualKeyVO vo = inv.getArgument(1);
            vo.setId(7L);
            return vo;
        });

        // 合法：trim 归一后透传
        VirtualKeyVO ok = service.create(VirtualKeyCommandEntity.builder()
                .keyName("with-models")
                .allowedModels(java.util.List.of(" GPT-4O ", "deepseek-v4-pro"))
                .build());
        assertEquals(java.util.List.of("GPT-4O", "deepseek-v4-pro"), ok.getAllowedModels());

        // 非法：空白条目拒绝
        assertThrows(cn.chyuan.ai.types.exception.AppException.class,
                () -> service.create(VirtualKeyCommandEntity.builder()
                        .keyName("bad").allowedModels(java.util.List.of("gpt-4o", "  ")).build()));

        // 空清单 = 不限制（归一为 null，兼容存量）
        VirtualKeyVO unlimited = service.create(VirtualKeyCommandEntity.builder()
                .keyName("no-models").allowedModels(java.util.List.of()).build());
        assertNull(unlimited.getAllowedModels());
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

    @Test
    @DisplayName("轮换（0049）— 新明文一次返回、仓储换哈希+宽限、缓存全量失效、审计")
    void testRegenerate_NewPlaintextOnceAndRotate() throws Exception {
        java.lang.reflect.Field graceField = VirtualKeyService.class.getDeclaredField("rotationGraceHours");
        graceField.setAccessible(true);
        graceField.set(service, 24L);

        when(repository.findById(42L)).thenReturn(VirtualKeyVO.builder()
                .id(42L).keyName("k").status("ACTIVE").rotationCount(1).build());
        when(repository.findById(42L)).thenReturn(VirtualKeyVO.builder()
                .id(42L).keyName("k").status("ACTIVE").rotationCount(1).build());
        // regenerate 内部再 getById → 返回已轮换形态
        when(repository.findById(42L)).thenReturn(VirtualKeyVO.builder()
                .id(42L).keyName("k").status("ACTIVE").rotationCount(2).build());

        VirtualKeyVO result = service.regenerate(42L);

        assertNotNull(result.getPlaintextOnce());
        assertTrue(result.getPlaintextOnce().startsWith("vk-"));
        org.mockito.ArgumentCaptor<java.util.Date> graceCaptor = org.mockito.ArgumentCaptor.forClass(java.util.Date.class);
        verify(repository).rotateKey(org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq(KeyHashUtil.sha256Hex(result.getPlaintextOnce())), graceCaptor.capture());
        assertTrue(graceCaptor.getValue().getTime() > System.currentTimeMillis() + 23 * 3600_000L, "宽限期约 24h");
        verify(governanceAuthService).invalidateAll();
        verify(auditService).record(any(AuditCommandEntity.class));
    }

    @Test
    @DisplayName("轮换（0049）— 非启用态拒绝")
    void testRegenerate_RejectsDisabledKey() {
        when(repository.findById(42L)).thenReturn(VirtualKeyVO.builder().id(42L).status("DISABLED").build());
        assertThrows(cn.chyuan.ai.types.exception.AppException.class, () -> service.regenerate(42L));
        verify(repository, never()).rotateKey(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("block/unblock（0052）— 状态切换 + 立即失效缓存 + 审计")
    void testBlockUnblock() {
        when(repository.findById(42L)).thenReturn(VirtualKeyVO.builder().id(42L).status("ACTIVE").build());
        service.block(42L);
        verify(repository).updateStatus(42L, "DISABLED");
        verify(governanceAuthService).invalidateAll();

        service.unblock(42L);
        verify(repository).updateStatus(42L, "ACTIVE");
        verify(governanceAuthService, times(2)).invalidateAll();
    }

    @Test
    @DisplayName("批量（0052）— 部分失败跳过并返回成功数")
    void testBulkUpdateStatus() {
        when(repository.findById(1L)).thenReturn(VirtualKeyVO.builder().id(1L).status("ACTIVE").build());
        when(repository.findById(2L)).thenReturn(null);
        when(repository.findById(2L)).thenReturn(null);

        int changed = service.bulkUpdateStatus(java.util.List.of(1L, 2L), true);

        assertEquals(1, changed);
        verify(repository).updateStatus(1L, "DISABLED");
    }

    @Test
    @DisplayName("临时提额（0052）— 未配置预算拒绝；合法入参落库 + 审计")
    void testApplyTempBudget() {
        when(repository.findById(1L)).thenReturn(VirtualKeyVO.builder().id(1L).status("ACTIVE").build());
        assertThrows(cn.chyuan.ai.types.exception.AppException.class,
                () -> service.applyTempBudget(1L, 100, new java.util.Date(System.currentTimeMillis() + 3600_000)));

        when(repository.findById(2L)).thenReturn(VirtualKeyVO.builder().id(2L).status("ACTIVE")
                .budgetHard(1000L).build());
        java.util.Date expiry = new java.util.Date(System.currentTimeMillis() + 7200_000);
        service.applyTempBudget(2L, 500, expiry);
        verify(repository).applyTempBudget(2L, 500, expiry);
        verify(auditService).record(any(AuditCommandEntity.class));
    }
}

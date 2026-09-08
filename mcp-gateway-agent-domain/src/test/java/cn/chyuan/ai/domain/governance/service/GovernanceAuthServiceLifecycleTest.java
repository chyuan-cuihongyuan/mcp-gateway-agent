package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.auth.adapter.repository.IAuthRepository;
import cn.chyuan.ai.domain.auth.model.valobj.enums.AuthStatusEnum;
import cn.chyuan.ai.domain.governance.adapter.codec.IJwtCodec;
import cn.chyuan.ai.domain.governance.adapter.repository.IVirtualKeyRepository;
import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import cn.chyuan.ai.domain.governance.model.valobj.VirtualKeyVO;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import cn.chyuan.ai.types.exception.AppException;
import cn.chyuan.ai.types.util.KeyHashUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 统一认证服务密钥生命周期测试（工单 0045：过期/禁用/IP 白名单四态错误码 + last_active 去抖）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("治理面认证服务 — 密钥生命周期测试")
public class GovernanceAuthServiceLifecycleTest {

    private static final String GATEWAY = "gateway_001";
    private static final String CREDENTIAL = "vk-test-credential";

    @Mock
    private IAuthRepository authRepository;

    @Mock
    private IVirtualKeyRepository virtualKeyRepository;

    @Mock
    private IJwtCodec jwtCodec;

    @InjectMocks
    private GovernanceAuthService service;

    @BeforeEach
    public void setUp() {
        service.init();
        lenient().when(authRepository.queryGatewayAuthStatus(GATEWAY))
                .thenReturn(AuthStatusEnum.GatewayConfig.STRONG_VERIFIED);
        lenient().when(virtualKeyRepository.existsGrant(anyLong(), anyString())).thenReturn(true);
    }

    private void mockKey(VirtualKeyVO vo) {
        when(virtualKeyRepository.findByHash(KeyHashUtil.sha256Hex(CREDENTIAL))).thenReturn(vo);
    }

    @Test
    @DisplayName("过期密钥 — KEY_EXPIRED(-32011)，与无权限(-32006)可区分")
    public void testExpiredKey_DistinctCode() {
        mockKey(activeKey().expiresAt(new Date(System.currentTimeMillis() - 60_000)).build());

        AppException e = assertThrows(AppException.class,
                () -> service.authenticate(GATEWAY, CREDENTIAL, "1.2.3.4"));
        assertEquals(String.valueOf(McpErrorCodes.KEY_EXPIRED), e.getCode());
    }

    @Test
    @DisplayName("禁用/吊销密钥 — KEY_DISABLED(-32012)")
    public void testDisabledKey_DistinctCode() {
        mockKey(activeKey().status("DISABLED").build());

        AppException e = assertThrows(AppException.class,
                () -> service.authenticate(GATEWAY, CREDENTIAL, "1.2.3.4"));
        assertEquals(String.valueOf(McpErrorCodes.KEY_DISABLED), e.getCode());
    }

    @Test
    @DisplayName("IP 白名单 — 命中放行 / 未命中 IP_NOT_ALLOWED(-32013) / 空 IP fail-closed")
    public void testIpAllowList() {
        mockKey(activeKey().ipAllowList(List.of("10.0.0.0/8")).build());
        GovernancePrincipal principal = service.authenticate(GATEWAY, CREDENTIAL, "10.1.2.3");
        assertEquals(GovernancePrincipal.AuthType.VIRTUAL_KEY, principal.getAuthType());

        mockKey(activeKey().ipAllowList(List.of("10.0.0.0/8")).build());
        AppException denied = assertThrows(AppException.class,
                () -> service.authenticate(GATEWAY, CREDENTIAL, "11.0.0.1"));
        assertEquals(String.valueOf(McpErrorCodes.IP_NOT_ALLOWED), denied.getCode());

        AppException noIp = assertThrows(AppException.class,
                () -> service.authenticate(GATEWAY, CREDENTIAL, null));
        assertEquals(String.valueOf(McpErrorCodes.IP_NOT_ALLOWED), noIp.getCode());
    }

    @Test
    @DisplayName("无白名单 — 任意来源放行（现状兼容）")
    public void testNoAllowList_Passes() {
        mockKey(activeKey().build());
        assertNotNull(service.authenticate(GATEWAY, CREDENTIAL, "8.8.8.8"));
        assertNotNull(service.authenticate(GATEWAY, CREDENTIAL, null));
    }

    @Test
    @DisplayName("last_active 去抖 — 同 key 60 秒内只写一次库")
    public void testLastActiveDebounce() {
        mockKey(activeKey().build());
        when(virtualKeyRepository.existsGrant(anyLong(), eq(GATEWAY))).thenReturn(true);

        service.authenticate(GATEWAY, CREDENTIAL, "1.2.3.4");
        service.authenticate(GATEWAY, CREDENTIAL, "1.2.3.4");
        service.authenticate(GATEWAY, CREDENTIAL, "1.2.3.4");

        verify(virtualKeyRepository, times(1)).touchLastActive(42L);
    }

    @Test
    @DisplayName("touch 失败不影响认证主链")
    public void testTouchFailure_DoesNotBreakAuth() {
        mockKey(activeKey().build());
        when(virtualKeyRepository.existsGrant(anyLong(), eq(GATEWAY))).thenReturn(true);
        doThrow(new RuntimeException("db down")).when(virtualKeyRepository).touchLastActive(anyLong());

        assertNotNull(service.authenticate(GATEWAY, CREDENTIAL, "1.2.3.4"));
    }

    private VirtualKeyVO.VirtualKeyVOBuilder activeKey() {
        return VirtualKeyVO.builder()
                .id(42L)
                .keyName("k")
                .status("ACTIVE")
                .rpmLimit(60);
    }
}

package cn.chyuan.ai.trigger.filter;

import cn.chyuan.ai.domain.governance.adapter.codec.IJwtCodec;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import cn.chyuan.ai.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * admin JWT 过滤器角色行为测试（工单 0017 验收：管理员可写、只读仅查）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("admin JWT 认证过滤器测试")
public class AdminJwtAuthFilterTest {

    @Mock
    private IJwtCodec jwtCodec;

    private AdminJwtAuthFilter filter;

    @BeforeEach
    public void setUp() {
        filter = new AdminJwtAuthFilter(jwtCodec);
    }

    @Test
    @DisplayName("缺 token → HTTP 401")
    public void testMissingToken_Returns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/v1/virtual-keys");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus(), "缺少 Bearer JWT 应返回 401");
        assertNull(chain.getRequest());
    }

    @Test
    @DisplayName("无效 token → HTTP 401")
    public void testInvalidToken_Returns401() throws Exception {
        when(jwtCodec.verify("bad-token")).thenThrow(new AppException(McpErrorCodes.INSUFFICIENT_PERMISSIONS, "过期"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/v1/virtual-keys");
        request.addHeader("Authorization", "Bearer bad-token");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNull(chain.getRequest(), "无效 token 不应进入链");
    }

    @Test
    @DisplayName("只读角色写操作 → HTTP 403（READONLY 仅查）")
    public void testReadonlyRole_WriteForbidden() throws Exception {
        when(jwtCodec.verify("ro-token")).thenReturn(new IJwtCodec.JwtClaims("viewer", List.of("READONLY"), 0));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/v1/virtual-keys");
        request.addHeader("Authorization", "Bearer ro-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus(), "只读角色写操作应被拒绝");
        assertNull(chain.getRequest());
    }

    @Test
    @DisplayName("只读角色读操作 → 放行并写入角色属性")
    public void testReadonlyRole_ReadAllowed() throws Exception {
        when(jwtCodec.verify("ro-token")).thenReturn(new IJwtCodec.JwtClaims("viewer", List.of("READONLY"), 0));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/v1/virtual-keys");
        request.addHeader("Authorization", "Bearer ro-token");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest(), "只读角色读操作应放行");
        assertEquals("READONLY", request.getAttribute(AdminJwtAuthFilter.ADMIN_ROLE_ATTR));
    }

    @Test
    @DisplayName("管理员角色写操作 → 放行")
    public void testAdminRole_WriteAllowed() throws Exception {
        when(jwtCodec.verify("admin-token")).thenReturn(new IJwtCodec.JwtClaims("admin", List.of("ADMIN"), 0));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/v1/virtual-keys");
        request.addHeader("Authorization", "Bearer admin-token");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest(), "管理员写操作应放行");
        assertEquals("ADMIN", request.getAttribute(AdminJwtAuthFilter.ADMIN_ROLE_ATTR));
    }

    @Test
    @DisplayName("登录接口 → 免 JWT 放行")
    public void testLoginPath_BypassesAuth() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/v1/auth/login");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest(), "登录接口应免认证放行");
        verifyNoInteractions(jwtCodec);
    }

    // ---- 工单 0109：四角色 × 端点组矩阵 ----

    private boolean allowed(String role, String method, String path) throws Exception {
        when(jwtCodec.verify("t")).thenReturn(new IJwtCodec.JwtClaims("u", List.of(role), 0));
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader("Authorization", "Bearer t");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return chain.getRequest() != null;
    }

    @Test
    @DisplayName("矩阵：读类端点四角色全放行（READONLY 含）")
    public void matrixReadEndpoints_AllRolesAllowed() throws Exception {
        for (String role : List.of("SUPER_ADMIN", "ADMIN", "AUDITOR", "READONLY")) {
            assertTrue(allowed(role, "GET", "/admin/v1/virtual-keys"), role + " 应可读");
            assertTrue(allowed(role, "GET", "/admin/v1/audit-logs"), role + " 应可读审计");
        }
    }

    @Test
    @DisplayName("矩阵：治理写操作 ADMIN 起（AUDITOR/READONLY 403）")
    public void matrixWrite_GovernanceAdminRoleRequired() throws Exception {
        assertTrue(allowed("SUPER_ADMIN", "POST", "/admin/v1/virtual-keys"));
        assertTrue(allowed("ADMIN", "POST", "/admin/v1/virtual-keys"));
        assertFalse(allowed("AUDITOR", "POST", "/admin/v1/virtual-keys"));
        assertFalse(allowed("READONLY", "POST", "/admin/v1/virtual-keys"));
    }

    @Test
    @DisplayName("矩阵：用量查询 AUDITOR 起（READONLY 403）")
    public void matrixUsage_AuditorRoleRequired() throws Exception {
        assertTrue(allowed("SUPER_ADMIN", "GET", "/admin/v1/usage/logs"));
        assertTrue(allowed("ADMIN", "GET", "/admin/v1/usage/logs"));
        assertTrue(allowed("AUDITOR", "GET", "/admin/v1/usage/logs"));
        assertFalse(allowed("READONLY", "GET", "/admin/v1/usage/logs"));
    }

    @Test
    @DisplayName("矩阵：配置导出 AUDITOR 起、导入 SUPER_ADMIN 专属")
    public void matrixConfig_ExportAuditorImportSuperAdmin() throws Exception {
        assertTrue(allowed("AUDITOR", "GET", "/admin/v1/config/export"));
        assertFalse(allowed("READONLY", "GET", "/admin/v1/config/export"));
        assertTrue(allowed("SUPER_ADMIN", "POST", "/admin/v1/config/import"));
        assertFalse(allowed("ADMIN", "POST", "/admin/v1/config/import"));
    }

    @Test
    @DisplayName("矩阵：用户管理端点 SUPER_ADMIN 专属（ADMIN 403 且提示所需角色）")
    public void matrixUsers_SuperAdminOnly() throws Exception {
        assertTrue(allowed("SUPER_ADMIN", "GET", "/admin/v1/users"));
        assertFalse(allowed("ADMIN", "POST", "/admin/v1/users"));

        when(jwtCodec.verify("t")).thenReturn(new IJwtCodec.JwtClaims("u", List.of("ADMIN"), 0));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/v1/users");
        request.addHeader("Authorization", "Bearer t");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("SUPER_ADMIN"), "403 应提示所需角色");
    }

    @Test
    @DisplayName("矩阵：多角色取最高秩；未知角色按 READONLY 兜底")
    public void matrixMultiRole_TakesHighest() throws Exception {
        when(jwtCodec.verify("t")).thenReturn(new IJwtCodec.JwtClaims("u", List.of("READONLY", "ADMIN"), 0));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/v1/virtual-keys");
        request.addHeader("Authorization", "Bearer t");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest(), "多角色取最高秩（ADMIN）应可写");

        when(jwtCodec.verify("t")).thenReturn(new IJwtCodec.JwtClaims("u", List.of("UNKNOWN_ROLE"), 0));
        MockHttpServletRequest unknown = new MockHttpServletRequest("POST", "/admin/v1/virtual-keys");
        unknown.addHeader("Authorization", "Bearer t");
        MockFilterChain unknownChain = new MockFilterChain();
        filter.doFilter(unknown, new MockHttpServletResponse(), unknownChain);
        assertNull(unknownChain.getRequest(), "未知角色按 READONLY 兜底应拒写");
    }
}

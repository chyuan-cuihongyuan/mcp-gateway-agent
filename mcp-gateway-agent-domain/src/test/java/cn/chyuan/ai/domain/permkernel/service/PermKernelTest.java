package cn.chyuan.ai.domain.permkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 能力许可内核测试（工单 0862-0869 CX1-CX8，deno 思想）。
 * --allow 标志解析/白名单粒度/拒绝与放行/授予幂等/令牌签发过期撤销/审计流水/端口与 vault 令牌联动。
 */
class PermKernelTest {

    @Test
    void flagParseForms() {
        List<PermFlags.Scope> scopes = PermFlags.parse(List.of(
                "--allow-read", "--allow-net=example.com:443,api.example.com", "--allow-env"));
        assertEquals(4, scopes.size(), "四类许可全量返回");
        PermFlags.Scope read = scopes.get(0);
        assertEquals(PermFlags.Mode.ALL, read.mode(), "无值即 ALL");
        PermFlags.Scope net = scopes.get(1);
        assertEquals(PermFlags.Mode.LIST, net.mode());
        assertEquals(List.of("example.com:443", "api.example.com"), net.entries());
        PermFlags.Scope run = scopes.get(3);
        assertEquals(PermFlags.Mode.NONE, run.mode(), "缺失即 NONE");
        assertThrows(IllegalArgumentException.class,
                () -> PermFlags.parse(List.of("--seed=1")), "非 allow 标志拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> PermFlags.parse(List.of("--allow-frob")), "未知许可类别拒绝");
    }

    @Test
    void whitelistNetMatching() {
        assertTrue(PermFlags.matches(PermFlags.Op.NET, "example.com:443", "example.com:443"), "精确宿主端口");
        assertFalse(PermFlags.matches(PermFlags.Op.NET, "example.com:443", "example.com:80"), "端口不符拒绝");
        assertTrue(PermFlags.matches(PermFlags.Op.NET, "example.com", "example.com:9999"), "无端口条目任意端口");
        assertTrue(PermFlags.matches(PermFlags.Op.NET, "*.example.com", "api.example.com:443"), "星号后缀通配");
        assertFalse(PermFlags.matches(PermFlags.Op.NET, "*.example.com", "example.org"), "通配不跨域");
    }

    @Test
    void whitelistPathPrefix() {
        assertTrue(PermFlags.matches(PermFlags.Op.READ, "/tmp", "/tmp/a.log"), "路径前缀");
        assertFalse(PermFlags.matches(PermFlags.Op.READ, "/tmp", "/etc/passwd"), "前缀不符拒绝");
        assertTrue(PermFlags.matches(PermFlags.Op.ENV, "HOME", "HOME"), "环境变量精确");
        assertFalse(PermFlags.matches(PermFlags.Op.ENV, "HOME", "PATH"));
    }

    @Test
    void checkFlows() {
        Authorizer az = new Authorizer(PermFlags.parse(List.of("--allow-read=/tmp", "--allow-net")));
        assertTrue(az.check(PermFlags.Op.READ, "/tmp/a.log").allowed(), "LIST 白名单命中");
        Authorizer.Decision denied = az.check(PermFlags.Op.READ, "/etc/passwd");
        assertFalse(denied.allowed());
        assertEquals("E_PERM_DENIED", denied.code());
        assertTrue(az.check(PermFlags.Op.NET, "any.host:1").allowed(), "ALL 放行");
        assertTrue(az.check(PermFlags.Op.RUN, "git").code().startsWith("E_PERM_NONE"), "NONE 拒绝");
    }

    @Test
    void grantIdempotentAndExtend() {
        Authorizer az = new Authorizer(PermFlags.parse(List.of("--allow-read=/tmp")));
        az.grant(PermFlags.Op.READ, List.of("/var"));
        az.grant(PermFlags.Op.READ, List.of("/var"));
        assertTrue(az.check(PermFlags.Op.READ, "/var/log").allowed(), "授予后放行");
        Authorizer fresh = new Authorizer(PermFlags.parse(List.of("--allow-read=/tmp")));
        fresh.grant(PermFlags.Op.READ, List.of("/var"));
        fresh.grant(PermFlags.Op.READ, List.of("/var"));
        assertTrue(az.check(PermFlags.Op.READ, "/tmp/x").allowed(), "原条目保留");
        Authorizer none = new Authorizer(PermFlags.parse(List.of()));
        none.grant(PermFlags.Op.RUN, List.of("git"));
        assertTrue(none.check(PermFlags.Op.RUN, "git").allowed(), "NONE 授予转 LIST");
    }

    @Test
    void revokeGrantImmediate() {
        Authorizer az = new Authorizer(PermFlags.parse(List.of("--allow-read")));
        assertTrue(az.check(PermFlags.Op.READ, "/x").allowed());
        az.revokeGrant(PermFlags.Op.READ);
        assertFalse(az.check(PermFlags.Op.READ, "/x").allowed(), "撤销立即拒绝");
        az.revokeGrant(PermFlags.Op.READ);
        assertFalse(az.check(PermFlags.Op.READ, "/x").allowed(), "重复撤销幂等");
    }

    @Test
    void tokenIssueAndValidate() {
        Authorizer az = new Authorizer(PermFlags.parse(List.of()));
        String tk = az.issue("agent-1",
                Map.of(PermFlags.Op.NET, PermFlags.Scope.list(PermFlags.Op.NET, List.of("api.example.com:443"))),
                5);
        assertTrue(az.checkToken(tk, PermFlags.Op.NET, "api.example.com:443").allowed());
        assertFalse(az.checkToken(tk, PermFlags.Op.READ, "/tmp").allowed(), "令牌未含 READ");
        assertFalse(az.checkToken(tk, PermFlags.Op.NET, "evil.com:443").allowed(), "令牌白名单未命中");
        String zeroTtl = az.issue("s", Map.of(PermFlags.Op.READ, PermFlags.Scope.all(PermFlags.Op.READ)), 0);
        assertEquals("E_TOKEN_EXPIRED", az.checkToken(zeroTtl, PermFlags.Op.READ, "/tmp").code(),
                "ttl 0 立即过期");
        assertFalse(az.checkToken("nope", PermFlags.Op.NET, "api.example.com:443").allowed(), "未知令牌拒绝");
    }

    @Test
    void tokenExpiryBySteps() {
        Authorizer az = new Authorizer(PermFlags.parse(List.of()));
        String tk = az.issue("s", Map.of(PermFlags.Op.READ, PermFlags.Scope.all(PermFlags.Op.READ)), 2);
        assertTrue(az.checkToken(tk, PermFlags.Op.READ, "/tmp").allowed());
        az.advance();
        assertTrue(az.checkToken(tk, PermFlags.Op.READ, "/tmp").allowed(), "到期边界内");
        az.advance();
        Authorizer.Decision expired = az.checkToken(tk, PermFlags.Op.READ, "/tmp");
        assertEquals("E_TOKEN_EXPIRED", expired.code(), "到期即拒绝");
    }

    @Test
    void revokeTokenImmediateIdempotent() {
        Authorizer az = new Authorizer(PermFlags.parse(List.of()));
        String tk = az.issue("s", Map.of(PermFlags.Op.READ, PermFlags.Scope.all(PermFlags.Op.READ)), 10);
        assertTrue(az.checkToken(tk, PermFlags.Op.READ, "/x").allowed());
        az.revokeToken(tk);
        assertEquals("E_TOKEN_REVOKED", az.checkToken(tk, PermFlags.Op.READ, "/x").code(), "撤销立即拒绝");
        assertDoesNotThrow(() -> az.revokeToken(tk), "重复撤销幂等");
        assertEquals("E_TOKEN_REVOKED", az.checkToken(tk, PermFlags.Op.READ, "/x").code());
    }

    @Test
    void auditTrailAndFilter() {
        Authorizer az = new Authorizer(PermFlags.parse(List.of("--allow-read=/tmp")));
        az.grant(PermFlags.Op.READ, List.of("/var"));
        az.check(PermFlags.Op.READ, "/tmp/a");
        az.check(PermFlags.Op.READ, "/etc");
        az.revokeGrant(PermFlags.Op.READ);
        assertEquals(1, az.auditFiltered(Authorizer.AuditKind.GRANT, "READ").size());
        assertEquals(1, az.auditFiltered(Authorizer.AuditKind.REVOKE, null).size());
        assertEquals(1, az.auditFiltered(Authorizer.AuditKind.DENY, "READ").size(), "拒绝事件");
        assertEquals(4, az.auditAll().size(), "全量流水：授予+放行+拒绝+撤销");
    }

    @Test
    void portOrchestrationAndVaultLinkage() {
        PermPort port = PermPort.inMemory();
        Authorizer az = port.authorizer(port.parse(List.of("--allow-net=api.example.com")));
        assertTrue(az.check(PermFlags.Op.NET, "api.example.com:443").allowed());
        String tk = port.issueFromVault(az, "vsig-9f2c",
                Map.of(PermFlags.Op.READ, PermFlags.Scope.list(PermFlags.Op.READ, List.of("/data"))), 5);
        assertTrue(az.checkToken(tk, PermFlags.Op.READ, "/data/x").allowed());
        assertTrue(az.auditAll().stream().anyMatch(e -> e.resource().equals("vault:vsig-9f2c")),
                "vault 令牌串作签发主体只读联动");
    }
}

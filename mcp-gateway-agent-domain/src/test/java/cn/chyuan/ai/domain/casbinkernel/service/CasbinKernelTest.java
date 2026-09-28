package cn.chyuan.ai.domain.casbinkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 策略授权内核测试（工单 0952-0959 EC1-EC8，casbin 思想）。
 * model 解析/policy 加载/角色继承/匹配函数/enforce 求值/effect 算子/域租户/端口增量通知。
 */
class CasbinKernelTest {

    private static final String BASIC_MODEL = """
            [request_definition]
            r = sub, obj, act

            [policy_definition]
            p = sub, obj, act

            [policy_effect]
            e = some(where (p.eft == allow))

            [matchers]
            m = r.sub == p.sub && r.obj == p.obj && r.act == p.act
            """;

    @Test
    void modelParseAndReject() {
        CasbinModel model = CasbinModel.parse(BASIC_MODEL);
        assertEquals(List.of("sub", "obj", "act"), model.requestTokens);
        assertEquals(CasbinModel.Effect.ALLOW_OVERRIDES, model.effect);
        assertEquals(3, model.matcher.size(), "三原子合取");

        String missing = BASIC_MODEL.replace("[matchers]", "[matcher]");
        assertThrows(IllegalArgumentException.class, () -> CasbinModel.parse(missing), "缺节拒绝");
        String unknown = BASIC_MODEL + "\n[priority_definition]\n";
        assertThrows(IllegalArgumentException.class, () -> CasbinModel.parse(unknown), "未知节拒绝");
        String badEffect = BASIC_MODEL.replace("some(where (p.eft == allow))", "priority(alice)");
        assertThrows(IllegalArgumentException.class, () -> CasbinModel.parse(badEffect), "未知 effect 拒绝");
        String badFn = BASIC_MODEL.replace("r.sub == p.sub", "fuzzy(r.sub, p.sub)");
        assertThrows(IllegalArgumentException.class, () -> CasbinModel.parse(badFn), "未知函数拒绝");
    }

    @Test
    void policyLoadAndReject() {
        CasbinEngine engine = new CasbinEngine(CasbinModel.parse(BASIC_MODEL));
        engine.loadPolicy("p, alice, data1, read");
        engine.loadPolicy("p, bob, data2, write");
        assertEquals(2, engine.pRows());
        assertThrows(IllegalArgumentException.class, () -> engine.loadPolicy("p, alice, data1, read"), "重复策略拒绝");
        assertThrows(IllegalArgumentException.class, () -> engine.loadPolicy("p, alice, data1"), "位数不符拒绝");
        assertThrows(IllegalArgumentException.class, () -> engine.loadPolicy("x, alice, data1, read"), "未知前缀拒绝");
        assertThrows(IllegalArgumentException.class, () -> engine.loadPolicy("p, , data1, read"), "空 token 拒绝");
    }

    @Test
    void roleInheritanceTransitiveAndCycle() {
        RoleManager roles = new RoleManager();
        roles.link("alice", "admin");
        roles.link("bob", "users");
        roles.link("users", "people");
        assertTrue(roles.hasLink("alice", "admin"), "直连");
        assertTrue(roles.hasLink("alice", "alice"), "自身可达");
        assertTrue(roles.hasLink("bob", "people"), "传递闭包");
        assertFalse(roles.hasLink("alice", "users"), "无此链路");
        roles.link("alice", "admin");
        assertEquals(3, roles.edgeCount(), "重复加边幂等不增");
        assertThrows(IllegalArgumentException.class, () -> roles.link("admin", "admin"), "自环拒绝");
        assertThrows(IllegalArgumentException.class, () -> roles.link("admin", "alice"), "成环拒绝");
    }

    @Test
    void builtinMatchFunctions() {
        assertTrue(MatchFunctions.keyMatch("/foo/bar", "/foo/*"), "* 前缀命中");
        assertFalse(MatchFunctions.keyMatch("/foo/bar", "/baz/*"), "前缀不配");
        assertTrue(MatchFunctions.keyMatch("/data1", "/data1"), "无 * 全等");
        assertTrue(MatchFunctions.keyMatch2("/api/users/123", "/api/users/:id"), ":段占位命中");
        assertFalse(MatchFunctions.keyMatch2("/api/users/123/extra", "/api/users/:id"), "段数不齐");
        assertTrue(MatchFunctions.regexMatch("data9", "data[0-9]"), "正则全匹配");
        assertFalse(MatchFunctions.regexMatch("dataX", "data[0-9]"));
        assertThrows(IllegalArgumentException.class, () -> MatchFunctions.apply("fuzzy", "a", "b"), "未知函数兜底拒绝");
    }

    @Test
    void enforceRowMatching() {
        CasbinEngine engine = new CasbinEngine(CasbinModel.parse(BASIC_MODEL));
        engine.loadPolicy("p, alice, data1, read");
        assertTrue(engine.enforce(List.of("alice", "data1", "read")));
        assertFalse(engine.enforce(List.of("alice", "data1", "write")), "act 不符");
        assertFalse(engine.enforce(List.of("bob", "data1", "read")), "sub 不符");
        CasbinEngine pattern = new CasbinEngine(CasbinModel.parse(BASIC_MODEL
                .replace("r.obj == p.obj", "keyMatch(r.obj, p.obj)")));
        pattern.loadPolicy("p, alice, /data/*, read");
        assertTrue(pattern.enforce(List.of("alice", "/data/9/x", "read")), "keyMatch 原子求值");
        assertThrows(IllegalArgumentException.class, () -> engine.enforce(List.of("alice", "data1")), "请求位数不符拒绝");
    }

    @Test
    void effectOperatorsAndDefaultDeny() {
        String allowModel = BASIC_MODEL;
        String denyModel = BASIC_MODEL.replace(
                "e = some(where (p.eft == allow))", "e = !some(where (p.eft == deny))");
        CasbinEngine allow = new CasbinEngine(CasbinModel.parse(allowModel));
        allow.loadPolicy("p, alice, data1, read");
        assertTrue(allow.enforce(List.of("alice", "data1", "read")), "优先允许命中放行");
        assertFalse(allow.enforce(List.of("bob", "data1", "read")), "无命中默认 deny");

        CasbinEngine deny = new CasbinEngine(CasbinModel.parse(denyModel));
        deny.loadPolicy("p, alice, data1, read");
        assertFalse(deny.enforce(List.of("alice", "data1", "read")), "优先拒绝命中即拒");
        assertFalse(deny.enforce(List.of("bob", "data1", "read")), "无命中同样 deny");
    }

    @Test
    void domainTenantIsolation() {
        String domainModel = """
                [request_definition]
                r = sub, dom, obj, act

                [policy_definition]
                p = sub, dom, obj, act

                [policy_effect]
                e = some(where (p.eft == allow))

                [matchers]
                m = g2(r.sub, p.sub, r.dom) && r.dom == p.dom && r.obj == p.obj && r.act == p.act
                """;
        CasbinEngine engine = new CasbinEngine(CasbinModel.parse(domainModel));
        engine.loadPolicy("g2, alice, admin, tenant1");
        engine.loadPolicy("p, admin, tenant1, data1, read");
        assertTrue(engine.enforce(List.of("alice", "tenant1", "data1", "read")), "域内角色命中");
        assertFalse(engine.enforce(List.of("alice", "tenant2", "data1", "read")), "跨域不可见");
        assertFalse(engine.enforce(List.of("alice", "tenant1", "data1", "write")), "act 不符");
    }

    @Test
    void portIncrementAndNotify() {
        CasbinPort port = CasbinPort.inMemory();
        StringBuilder events = new StringBuilder();
        port.watch(events::append);
        port.loadModel(BASIC_MODEL);
        assertThrows(IllegalStateException.class, () -> CasbinPort.inMemory().enforce(List.of("a")), "未加载模型拒绝");
        port.loadPolicy(List.of("p, alice, data1, read"));
        assertTrue(port.enforce(List.of("alice", "data1", "read")));
        port.addPolicy("p, bob, data1, write");
        assertTrue(events.toString().contains("add:p, bob"), "增量通知");
        assertTrue(port.enforce(List.of("bob", "data1", "write")));
        assertThrows(IllegalArgumentException.class, () -> port.addPolicy("p, bob, data1, write"), "重复增量拒绝");
        port.removePolicy("p, bob, data1, write");
        assertTrue(events.toString().contains("remove:p, bob"), "移除通知");
        assertFalse(port.enforce(List.of("bob", "data1", "write")), "移除后失效");
        assertThrows(IllegalArgumentException.class, () -> port.removePolicy("p, bob, data1, write"), "不存在移除拒绝");
        assertEquals("perm://alice/read", CasbinPort.permitOf("alice", "read"), "permkernel 许可串形态只读联动");
    }
}

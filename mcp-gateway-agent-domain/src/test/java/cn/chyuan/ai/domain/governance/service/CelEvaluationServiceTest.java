package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.common.types.SimpleType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * CEL 求值服务测试（工单 0018 验收：三态 / AND 合并 / fail-closed / 拦截计数 / 作用域）
 *
 * <p>使用真实 cel-java 引擎编译规则，验证协议级语义。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CEL 求值服务测试")
public class CelEvaluationServiceTest {

    @Mock
    private ICelRuleService celRuleService;

    @InjectMocks
    private CelEvaluationService service;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private final Cel cel = CelFactory.standardCelBuilder()
            .addVar("auth", SimpleType.DYN)
            .addVar("key", SimpleType.DYN)
            .addVar("mcp", SimpleType.DYN)
            .build();

    @BeforeEach
    public void setUp() {
        ReflectionTestUtils.setField(service, "meterRegistry", meterRegistry);
    }

    private GovernancePrincipal vkPrincipal() {
        return GovernancePrincipal.builder()
                .authType(GovernancePrincipal.AuthType.VIRTUAL_KEY)
                .virtualKeyId(42L)
                .ownerUserId("user-a")
                .tenantId("tenant-1")
                .roles(List.of())
                .rpmLimit(60)
                .build();
    }

    private CompiledCelRule compiled(String expression, String scopeType, String gatewayId, Long virtualKeyId) {
        try {
            return new CompiledCelRule(1L, "rule-" + System.nanoTime(), scopeType, gatewayId, virtualKeyId,
                    cel.createProgram(cel.compile(expression).getAst()));
        } catch (Exception e) {
            throw new IllegalStateException("测试规则编译失败: " + expression, e);
        }
    }

    private void stubRules(List<CompiledCelRule> rules) {
        when(celRuleService.activeRuleSnapshot()).thenReturn(rules);
    }

    @Test
    @DisplayName("放行态 — 规则为 true 时工具可见可调（tools/list 与 tools/call 均放行）")
    public void testAllowed() {
        stubRules(List.of(compiled("mcp.tool.name != 'forbidden_tool'", "GLOBAL", null, null)));
        GovernancePrincipal principal = vkPrincipal();

        assertTrue(service.isToolAllowed(principal, "gw-1", "tools/list", "query_order", "PROTOCOL"));
        assertTrue(service.isToolAllowed(principal, "gw-1", "tools/call", "query_order", "PROTOCOL"));
        assertEquals(0.0, denialCount(), "放行不应计数");
    }

    @Test
    @DisplayName("隐藏态 — 规则按工具名拒绝：tools/list 与 tools/call 均拒绝并计数")
    public void testHidden() {
        stubRules(List.of(compiled("mcp.tool.name != 'secret_tool'", "GLOBAL", null, null)));
        GovernancePrincipal principal = vkPrincipal();

        assertFalse(service.isToolAllowed(principal, "gw-1", "tools/list", "secret_tool", "PROTOCOL"),
                "tools/list 应隐藏（求值 false）");
        assertFalse(service.isToolAllowed(principal, "gw-1", "tools/call", "secret_tool", "PROTOCOL"),
                "tools/call 应拒绝");
        assertEquals(2.0, denialCount(), "两次拒绝都应计数");
    }

    @Test
    @DisplayName("拦截态 — mcp.method 条件规则：清单可见但调用被拒")
    public void testInterceptOnCallOnly() {
        // tools/list 时恒放行；tools/call 且目标为 write_tool 时拒绝
        stubRules(List.of(compiled("!(mcp.method == 'tools/call' && mcp.tool.name == 'write_tool')",
                "GLOBAL", null, null)));
        GovernancePrincipal principal = vkPrincipal();

        assertTrue(service.isToolAllowed(principal, "gw-1", "tools/list", "write_tool", "PROTOCOL"),
                "清单阶段应可见");
        assertFalse(service.isToolAllowed(principal, "gw-1", "tools/call", "write_tool", "PROTOCOL"),
                "调用阶段应被拦截");
    }

    @Test
    @DisplayName("AND 合并 — 三层作用域全部适用，任一 false 即拒绝")
    public void testAndMergeAcrossScopes() {
        stubRules(List.of(
                compiled("auth.auth_type == 'VIRTUAL_KEY'", "GLOBAL", null, null),
                compiled("auth.tenant_id == 'tenant-1'", "GATEWAY", "gw-1", null),
                compiled("mcp.tool.name != 'write_tool'", "VIRTUAL_KEY", null, 42L)));
        GovernancePrincipal principal = vkPrincipal();

        assertTrue(service.isToolAllowed(principal, "gw-1", "tools/call", "query_order", "PROTOCOL"),
                "三层全 true 应放行");

        // 第三层翻 false → 拒绝
        assertFalse(service.isToolAllowed(principal, "gw-1", "tools/call", "write_tool", "PROTOCOL"));
    }

    @Test
    @DisplayName("作用域过滤 — 其它网关/其它密钥的规则不适用")
    public void testScopeFiltering() {
        stubRules(List.of(compiled("mcp.tool.name != 'write_tool'", "GATEWAY", "gw-2", null)));
        GovernancePrincipal principal = vkPrincipal();

        assertTrue(service.isToolAllowed(principal, "gw-1", "tools/call", "write_tool", "PROTOCOL"),
                "gw-2 的规则不应作用于 gw-1");
    }

    @Test
    @DisplayName("fail-closed — 求值异常（除零）按拒绝处理")
    public void testFailClosedOnEvaluationError() {
        stubRules(List.of(compiled("key.quota.rpm_limit > 100 / (mcp.tool.name.size() - mcp.tool.name.size())",
                "GLOBAL", null, null)));
        GovernancePrincipal principal = vkPrincipal();

        assertFalse(service.isToolAllowed(principal, "gw-1", "tools/call", "query_order", "PROTOCOL"),
                "求值异常应 fail-closed");
        assertEquals(1.0, denialCount());
    }

    @Test
    @DisplayName("fail-closed — 快照编译失败的规则（program=null）按拒绝处理")
    public void testFailClosedOnBrokenProgram() {
        stubRules(List.of(new CompiledCelRule(9L, "broken", "GLOBAL", null, null, null)));
        GovernancePrincipal principal = vkPrincipal();

        assertFalse(service.isToolAllowed(principal, "gw-1", "tools/call", "query_order", "PROTOCOL"));
    }

    @Test
    @DisplayName("fail-closed — 表达式结果非 bool 按拒绝处理")
    public void testFailClosedOnNonBooleanResult() {
        stubRules(List.of(compiled("mcp.tool.name", "GLOBAL", null, null)));
        GovernancePrincipal principal = vkPrincipal();

        assertFalse(service.isToolAllowed(principal, "gw-1", "tools/call", "query_order", "PROTOCOL"),
                "非 bool 结果应拒绝");
    }

    @Test
    @DisplayName("无适用规则 / 无认证主体 — 放行")
    public void testNoRulesOrNullPrincipal_Allowed() {
        stubRules(List.of());
        assertTrue(service.isToolAllowed(vkPrincipal(), "gw-1", "tools/call", "any", "PROTOCOL"));

        // 无认证主体（遗留路径）：不查规则直接放行
        assertTrue(service.isToolAllowed(null, "gw-1", "tools/call", "any", "PROTOCOL"));
    }

    @Test
    @DisplayName("变量面 — 0011 决议变量在表达式中可用")
    public void testDeclaredVariables() {
        stubRules(List.of(compiled(
                "auth.key_id == 42 && auth.owner_user_id == 'user-a' && auth.tenant_id == 'tenant-1'"
                        + " && auth.auth_type == 'VIRTUAL_KEY' && auth.roles.size() == 0"
                        + " && key.quota.rpm_limit == 60 && key.quota.daily_request_remaining == -1"
                        + " && mcp.gateway.id == 'gw-1' && mcp.method == 'tools/call'"
                        + " && mcp.tool.name == 'query_order' && mcp.tool.source == 'PROTOCOL'",
                "GLOBAL", null, null)));

        assertTrue(service.isToolAllowed(vkPrincipal(), "gw-1", "tools/call", "query_order", "PROTOCOL"),
                "全部 0011 变量应正确绑定");
    }

    private double denialCount() {
        return meterRegistry.counter("governance.cel.denied", "gateway", "gw-1", "method", "tools/call").count()
                + meterRegistry.counter("governance.cel.denied", "gateway", "gw-1", "method", "tools/list").count();
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("0048 扩展变量 — jwt.sub/jwt.roles/client.ip/mcp.tool.target 可用")
    void extendedVariablesEvaluate() {
        cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal jwt = cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal.builder()
                .authType(cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal.AuthType.JWT)
                .ownerUserId("user-1").roles(java.util.List.of("admin")).clientIp("10.1.2.3").build();
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                service.isToolAllowed(jwt, "gateway_business", "tools/call", "oil_applyRefund",
                        cn.chyuan.ai.domain.governance.service.CelEvaluationService.TOOL_SOURCE_EXTERNAL));

        // 直接对变量工厂断言绑定值（dry-run 与运行求值共用同一工厂）
        java.util.Map<String, Object> vars = cn.chyuan.ai.domain.governance.service.CelVariables.of(
                jwt, "gateway_business", "tools/call", "oil_applyRefund",
                cn.chyuan.ai.domain.governance.service.CelEvaluationService.TOOL_SOURCE_EXTERNAL);
        org.junit.jupiter.api.Assertions.assertEquals("user-1",
                ((java.util.Map<?, ?>) vars.get("jwt")).get("sub"));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("admin"),
                ((java.util.Map<?, ?>) vars.get("jwt")).get("roles"));
        org.junit.jupiter.api.Assertions.assertEquals("10.1.2.3",
                ((java.util.Map<?, ?>) vars.get("client")).get("ip"));
        java.util.Map<?, ?> tool = (java.util.Map<?, ?>) ((java.util.Map<?, ?>) vars.get("mcp")).get("tool");
        org.junit.jupiter.api.Assertions.assertEquals("oil", tool.get("target"), "EXTERNAL 工具 target=挂接渠道名");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("0048 缺主体不抛错 — 空形绑定（vk/匿名）")
    void extendedVariablesSafeForNullPrincipal() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                cn.chyuan.ai.domain.governance.service.CelVariables.of(null, "g", "tools/list", null, null));
        java.util.Map<String, Object> vars = cn.chyuan.ai.domain.governance.service.CelVariables.of(
                null, "g", "tools/list", null, null);
        org.junit.jupiter.api.Assertions.assertEquals("", ((java.util.Map<?, ?>) vars.get("jwt")).get("sub"));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(), ((java.util.Map<?, ?>) vars.get("jwt")).get("roles"));
    }
}

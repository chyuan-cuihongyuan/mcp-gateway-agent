package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.adapter.repository.ICelRuleRepository;
import cn.chyuan.ai.domain.governance.model.entity.AuditCommandEntity;
import cn.chyuan.ai.domain.governance.model.valobj.CelRuleVO;
import cn.chyuan.ai.types.exception.AppException;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * CEL 规则服务测试（工单 0018 验收：保存时编译校验 / 作用域校验 / 审计 / 快照）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CEL 规则服务测试")
public class CelRuleServiceTest {

    @Mock
    private ICelRuleRepository repository;

    @Mock
    private IAuditService auditService;

    @InjectMocks
    private CelRuleService service;

    @BeforeEach
    public void setUp() {
        // 单测无 Spring 注入，显式给 30s TTL（默认 0 会使缓存永不命中）
        ReflectionTestUtils.setField(service, "cacheTtlSeconds", 30L);
        service.init();
    }

    private CelRuleVO rule(String expression, String scopeType) {
        return CelRuleVO.builder()
                .ruleName("r-" + System.nanoTime())
                .expression(expression)
                .scopeType(scopeType)
                .status("ACTIVE")
                .build();
    }

    @Test
    @DisplayName("编译校验 — 合法表达式返回 null，未声明变量/语法错误返回原因")
    public void testValidateExpression() {
        assertNull(service.validateExpression("mcp.tool.name != 'x'"));
        assertNull(service.validateExpression("auth.auth_type == 'VIRTUAL_KEY' && key.quota.rpm_limit >= 0"));
        assertNull(service.validateExpression("mcp.method == 'tools/list' || 'a' in auth.roles"));

        String undeclared = service.validateExpression("bogus_var == 1");
        assertNotNull(undeclared, "未声明变量应编译失败");

        String syntaxError = service.validateExpression("mcp.tool.name !=");
        assertNotNull(syntaxError, "语法错误应编译失败");
    }

    @Test
    @DisplayName("创建规则 — 非法表达式拒绝入库并返回原因")
    public void testCreate_IllegalExpression_Rejected() {
        AppException ex = assertThrows(AppException.class,
                () -> service.create(rule("mcp.tool.name ==", "GLOBAL")));
        assertTrue(ex.getInfo().contains("编译失败"), "错误信息应含编译失败原因: " + ex.getInfo());
        verify(repository, never()).insert(any(CelRuleVO.class));
        verify(auditService, never()).record(any(AuditCommandEntity.class));
    }

    @Test
    @DisplayName("作用域校验 — GATEWAY 缺 gatewayId / 非法 scopeType 拒绝")
    public void testCreate_ScopeValidation() {
        AppException noGateway = assertThrows(AppException.class,
                () -> service.create(rule("true", "GATEWAY")));
        assertTrue(noGateway.getInfo().contains("gatewayId"));

        AppException badScope = assertThrows(AppException.class,
                () -> service.create(rule("true", "TENANT")));
        assertTrue(badScope.getInfo().contains("scopeType"));
    }

    @Test
    @DisplayName("创建规则 — 合法表达式入库、写审计、失效快照")
    public void testCreate_Valid_PersistsAndAudits() {
        CelRuleVO valid = rule("mcp.tool.name != 'x'", "GLOBAL");
        doAnswer(inv -> {
            inv.getArgument(0, CelRuleVO.class).setId(7L);
            return null;
        }).when(repository).insert(any(CelRuleVO.class));

        CelRuleVO saved = service.create(valid);

        assertEquals(7L, saved.getId());
        verify(auditService).record(any(AuditCommandEntity.class));
    }

    @Test
    @DisplayName("快照 — 活跃规则编译为可执行程序；库中坏规则标记 fail-closed（program=null）")
    public void testActiveRuleSnapshot() {
        when(repository.findAllActive()).thenReturn(List.of(
                rule("mcp.tool.name != 'x'", "GLOBAL"),
                rule("this is not (valid cel", "GLOBAL")));

        List<CompiledCelRule> snapshot = service.activeRuleSnapshot();

        assertEquals(2, snapshot.size());
        assertNotNull(snapshot.get(0).program(), "合法规则应有编译产物");
        assertNull(snapshot.get(1).program(), "坏规则应为 fail-closed 标记");
    }

    @Test
    @DisplayName("热更新 — 写操作后快照重建（30s TTL 之外的同实例即时失效路径）")
    public void testWriteInvalidatesSnapshot() {
        when(repository.findAllActive()).thenReturn(List.of(rule("mcp.tool.name != 'x'", "GLOBAL")));
        assertEquals(1, service.activeRuleSnapshot().size());

        when(repository.findAllActive()).thenReturn(List.of());
        // 未失效前命中缓存
        assertEquals(1, service.activeRuleSnapshot().size());

        CelRuleVO toDelete = CelRuleVO.builder().id(1L).ruleName("r").expression("true")
                .scopeType("GLOBAL").status("ACTIVE").build();
        when(repository.findById(1L)).thenReturn(toDelete);
        service.delete(1L);

        assertEquals(0, service.activeRuleSnapshot().size(), "删除后应即时失效并重建快照");
    }

    @Test
    @DisplayName("更新/删除不存在规则 — 拒绝")
    public void testUpdateDelete_MissingRule_Rejected() {
        when(repository.findById(99L)).thenReturn(null);
        assertThrows(AppException.class, () -> service.update(
                CelRuleVO.builder().id(99L).expression("true").scopeType("GLOBAL").build()));
        assertThrows(AppException.class, () -> service.delete(99L));
        verify(repository, never()).update(any(CelRuleVO.class));
        verify(repository, never()).deleteById(99L);
    }
}

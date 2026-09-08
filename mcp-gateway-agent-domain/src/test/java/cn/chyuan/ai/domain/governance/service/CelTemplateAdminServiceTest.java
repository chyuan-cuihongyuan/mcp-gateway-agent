package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.adapter.repository.ICelTemplateRepository;
import cn.chyuan.ai.domain.governance.model.valobj.CelRuleTemplateVO;
import cn.chyuan.ai.domain.governance.model.valobj.CelRuleVO;
import cn.chyuan.ai.domain.governance.service.IAuditService;
import cn.chyuan.ai.types.exception.AppException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CEL 规则模板服务测试（工单 0057：渲染/半渲染拒绝/内置保护/实例化走校验链）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CEL 规则模板服务测试")
public class CelTemplateAdminServiceTest {

    @Mock
    private ICelTemplateRepository repository;

    @Mock
    private ICelRuleService celRuleService;

    @Mock
    private IAuditService auditService;

    @InjectMocks
    private CelTemplateAdminService service;

    @Test
    @DisplayName("渲染 — 占位符替换与保留（未提供参数原样保留供残留校验）")
    public void testRender() {
        assertEquals("mcp.tool.name in [\"a\",\"b\"]",
                CelTemplateAdminService.render("mcp.tool.name in [{{toolsList}}]",
                        Map.of("toolsList", "\"a\",\"b\"")));
        assertEquals("client.ip.startsWith(\"{{prefix}}\")",
                CelTemplateAdminService.render("client.ip.startsWith(\"{{prefix}}\")", Map.of()),
                "未提供参数原样保留");
    }

    @Test
    @DisplayName("实例化 — 渲染完整即走规则保存链；半渲染拒绝")
    public void testInstantiate() {
        when(repository.findByCode("builtin-tool-whitelist")).thenReturn(CelRuleTemplateVO.builder()
                .code("builtin-tool-whitelist").name("工具白名单")
                .expression("mcp.tool.name in [{{toolsList}}]").build());
        when(celRuleService.create(any(CelRuleVO.class))).thenAnswer(inv -> inv.getArgument(0));

        CelRuleVO rule = service.instantiate("builtin-tool-whitelist", "白名单-业务网关",
                "GATEWAY", "gateway_001", null, Map.of("toolsList", "\"queryOrder\""));

        assertEquals("mcp.tool.name in [\"queryOrder\"]", rule.getExpression());
        assertEquals("GATEWAY", rule.getScopeType());
        verify(celRuleService).create(any(CelRuleVO.class));

        AppException e = assertThrows(AppException.class, () -> service.instantiate(
                "builtin-tool-whitelist", null, null, null, null, Map.of()));
        assertTrue(e.getInfo().contains("toolsList"), "半渲染应报缺失占位符");
    }

    @Test
    @DisplayName("内置模板保护 — 不可删除；builtin- 前缀不可自建")
    public void testBuiltinProtection() {
        when(repository.findById(1L)).thenReturn(CelRuleTemplateVO.builder()
                .id(1L).code("builtin-tool-whitelist").builtin(1).build());
        assertThrows(AppException.class, () -> service.delete(1L));

        assertThrows(AppException.class, () -> service.createCustom(CelRuleTemplateVO.builder()
                .code("builtin-hack").name("n").expression("true").build()));
    }

    @Test
    @DisplayName("自建模板 — 骨架以占位值校验可编译后才入库")
    public void testCreateCustomValidatesSkeleton() {
        when(repository.findByCode("mine")).thenReturn(null);
        when(celRuleService.validateExpression("mcp.tool.name in [\"probe\"]")).thenReturn(null);
        when(repository.insert(any(CelRuleTemplateVO.class))).thenReturn(9L);

        CelRuleTemplateVO created = service.createCustom(CelRuleTemplateVO.builder()
                .code("mine").name("自定义").expression("mcp.tool.name in [{{tools}}]").build());
        assertEquals(0, created.getBuiltin());

        when(celRuleService.validateExpression(any())).thenReturn("编译失败");
        assertThrows(AppException.class, () -> service.createCustom(CelRuleTemplateVO.builder()
                .code("mine2").name("x").expression("bad {{v}}").build()));
    }
}

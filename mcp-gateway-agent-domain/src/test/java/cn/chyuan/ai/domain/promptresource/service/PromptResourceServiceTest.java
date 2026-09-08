package cn.chyuan.ai.domain.promptresource.service;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import cn.chyuan.ai.domain.governance.service.ICelEvaluationService;
import cn.chyuan.ai.domain.promptresource.adapter.repository.IPromptResourceRepository;
import cn.chyuan.ai.domain.promptresource.model.valobj.PromptVO;
import cn.chyuan.ai.domain.promptresource.model.valobj.ResourceVO;
import cn.chyuan.ai.types.exception.AppException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 网关本地 Prompt/Resource 服务测试（工单 0053：CEL 裁剪/门槛/参数渲染/缺参）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("本地 Prompt/Resource 服务测试")
public class PromptResourceServiceTest {

    @Mock
    private IPromptResourceRepository repository;

    @Mock
    private ICelEvaluationService celEvaluationService;

    @InjectMocks
    private PromptResourceService service;

    private static PromptVO prompt(String name, String args, String template) {
        return PromptVO.builder().gatewayId("g").name(name).description("d")
                .argumentsJson(args).template(template).status(1).build();
    }

    @Test
    @DisplayName("prompts/list — CEL 拒绝的提示不可见")
    public void testVisiblePromptsCelFiltered() {
        when(repository.findPrompts("g")).thenReturn(List.of(prompt("ok", null, "t"), prompt("secret", null, "t")));
        when(celEvaluationService.isToolAllowed(any(), eq("g"), eq("prompts/list"), eq("ok"), eq("PROMPT")))
                .thenReturn(true);
        when(celEvaluationService.isToolAllowed(any(), eq("g"), eq("prompts/list"), eq("secret"), eq("PROMPT")))
                .thenReturn(false);

        List<PromptVO> visible = service.visiblePrompts("g", null);
        assertEquals(1, visible.size());
        assertEquals("ok", visible.get(0).getName());
    }

    @Test
    @DisplayName("prompts/get — 模板实参渲染 + 未提供占位保留；缺必填参数 -32602")
    public void testGetPromptRenderingAndRequiredArg() {
        when(celEvaluationService.isToolAllowed(any(), eq("g"), eq("prompts/get"), eq("p"), eq("PROMPT")))
                .thenReturn(true);
        when(repository.findPrompt("g", "p")).thenReturn(prompt("p",
                "[{\"name\":\"city\",\"required\":true}]", "天气：{{city}}，单位：{{unit}}"));

        PromptResourceService.RenderedPrompt rendered =
                service.getPrompt("g", "p", Map.of("city", "上海"), null);
        assertEquals("天气：上海，单位：{{unit}}", rendered.renderedText(), "未提供参数原样保留");

        AppException missing = assertThrows(AppException.class,
                () -> service.getPrompt("g", "p", Map.of(), null));
        assertTrue(missing.getInfo().contains("city"));
    }

    @Test
    @DisplayName("prompts/get CEL 门槛 — 拒绝 -32006；未找到 -32005")
    public void testGetPromptDenyAndNotFound() {
        when(celEvaluationService.isToolAllowed(any(), eq("g"), eq("prompts/get"), eq("x"), eq("PROMPT")))
                .thenReturn(false);
        AppException denied = assertThrows(AppException.class,
                () -> service.getPrompt("g", "x", Map.of(), null));
        assertEquals(String.valueOf(cn.chyuan.ai.types.enums.McpErrorCodes.INSUFFICIENT_PERMISSIONS), denied.getCode());

        when(celEvaluationService.isToolAllowed(any(), eq("g"), eq("prompts/get"), eq("y"), eq("PROMPT")))
                .thenReturn(true);
        when(repository.findPrompt("g", "y")).thenReturn(null);
        AppException notFound = assertThrows(AppException.class,
                () -> service.getPrompt("g", "y", Map.of(), null));
        assertEquals(String.valueOf(cn.chyuan.ai.types.enums.McpErrorCodes.RESOURCE_NOT_FOUND), notFound.getCode());
    }

    @Test
    @DisplayName("resources/read — CEL 门槛 + 内容返回；禁用态不可见")
    public void testResourceRead() {
        when(celEvaluationService.isToolAllowed(any(), eq("g"), eq("resources/read"), eq("file:///a"), eq("RESOURCE")))
                .thenReturn(true);
        when(repository.findResource("g", "file:///a")).thenReturn(ResourceVO.builder()
                .gatewayId("g").uri("file:///a").name("a").mimeType("text/plain").content("hello").status(1).build());

        assertEquals("hello", service.readResource("g", "file:///a", null).getContent());

        when(repository.findResource("g", "file:///b")).thenReturn(ResourceVO.builder()
                .gatewayId("g").uri("file:///b").status(0).build());
        when(celEvaluationService.isToolAllowed(any(), eq("g"), eq("resources/read"), eq("file:///b"), eq("RESOURCE")))
                .thenReturn(true);
        assertThrows(AppException.class, () -> service.readResource("g", "file:///b", null));
    }

    @Test
    @DisplayName("resources/list — 禁用态过滤")
    public void testVisibleResourcesFilterDisabled() {
        when(repository.findResources("g")).thenReturn(List.of(
                ResourceVO.builder().gatewayId("g").uri("u1").status(1).build(),
                ResourceVO.builder().gatewayId("g").uri("u2").status(0).build()));
        when(celEvaluationService.isToolAllowed(any(), eq("g"), eq("resources/list"), eq("u1"), eq("RESOURCE")))
                .thenReturn(true);
        assertEquals(1, service.visibleResources("g", null).size());
    }
}

package cn.chyuan.ai.domain.promptresource.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提示模板渲染器单测（工单 0198 AA3）：占位符提取/嵌套检测/缺失变量/缓存。
 */
class PromptTemplateRendererTest {

    @Test
    void 占位符提取() {
        assertEquals(Set.of("name", "city"),
                PromptTemplateRenderer.extractVariables("你好 {{name}}，来自 {{city}} 的 {{name}}"));
        assertTrue(PromptTemplateRenderer.extractVariables(null).isEmpty());
        assertTrue(PromptTemplateRenderer.extractVariables("无占位符").isEmpty());
    }

    @Test
    void 严格渲染与缺失报错() {
        Map<String, String> vars = Map.of("name", "小红");
        assertEquals("你好 小红", PromptTemplateRenderer.renderStrict("你好 {{name}}", vars));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PromptTemplateRenderer.renderStrict("你好 {{name}} {{age}}", vars));
        assertTrue(ex.getMessage().contains("age"));
    }

    @Test
    void 嵌套与悬挂占位检测() {
        // 嵌套 {{a{{b}}}}：剔除合法占位 {{b}} 后残留 {{a{ → 报错
        assertTrue(PromptTemplateRenderer.validate("{{a{{b}}}}", Set.of("a", "b")).stream()
                .anyMatch(e -> e.contains("嵌套")));
        // 悬挂 {{a}
        assertTrue(PromptTemplateRenderer.validate("{{a}", Set.of("a")).stream()
                .anyMatch(e -> e.contains("嵌套")));
        // 正常模板无结构错误
        List<String> ok = PromptTemplateRenderer.validate("你好 {{name}}", Set.of("name"));
        assertTrue(ok.isEmpty());
    }

    @Test
    void 缺失与多余变量校验() {
        List<String> errors = PromptTemplateRenderer.validate("{{a}} {{b}}", Set.of("b", "c"));
        assertTrue(errors.contains("缺少变量: a"));
        assertTrue(errors.contains("多余变量: c"));
    }

    @Test
    void 带缓存渲染命中() {
        PromptTemplateRenderer renderer = new PromptTemplateRenderer(16);
        Map<String, String> vars = new HashMap<>();
        vars.put("name", "x");
        assertEquals("hi x", renderer.render("hi {{name}}", vars));
        assertEquals("hi x", renderer.render("hi {{name}}", vars));
        assertEquals(1, renderer.cacheSize());
        // 不同变量集各自缓存
        Map<String, String> vars2 = Map.of("name", "y");
        assertEquals("hi y", renderer.render("hi {{name}}", vars2));
        assertEquals(2, renderer.cacheSize());
        // 校验失败不进缓存且抛错
        assertThrows(IllegalArgumentException.class, () -> renderer.render("hi {{who}}", vars));
        // LRU 容量淘汰：容量 1 时旧条目被逐出
        PromptTemplateRenderer tiny = new PromptTemplateRenderer(1);
        tiny.render("a {{v}}", Map.of("v", "1"));
        tiny.render("b {{v}}", Map.of("v", "2"));
        assertEquals(1, tiny.cacheSize());
    }
}

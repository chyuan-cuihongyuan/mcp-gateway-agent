package cn.chyuan.ai.domain.agent.service.armory.matter.mcp.server;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * b-09 工具清单契约（借鉴 modelcontextprotocol/inspector「契约先行」）：
 * MCP 工具对外暴露面整体校验——命名规约、描述完整性、参数文档、无重名。
 * 反射扫描 @Tool 注解，不启 Spring 上下文。
 */
class ToolManifestContractTest {

    private static final Class<?> TOOLS_CLASS = YunfanOilBusinessTools.class;
    private static final String NAME_PATTERN = "^agent_[a-z0-9_]+$";

    private List<Method> toolMethods() {
        List<Method> methods = new ArrayList<>();
        for (Method m : TOOLS_CLASS.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Tool.class)) {
                methods.add(m);
            }
        }
        return methods;
    }

    @Test
    void manifestNotEmpty() {
        // 工具清单非空下限：防「服务器意外注册 0 个工具」的静默退化
        assertThat(toolMethods().size()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void toolNamesFollowConventionAndAreUnique() {
        Set<String> seen = new HashSet<>();
        for (Method m : toolMethods()) {
            Tool tool = m.getAnnotation(Tool.class);
            String name = tool.name();
            assertThat(name).as("工具名 %s(%s)", name, m.getName()).matches(NAME_PATTERN);
            assertThat(seen.add(name)).as("工具名重复: %s", name).isTrue();
        }
    }

    @Test
    void everyToolHasMeaningfulDescription() {
        for (Method m : toolMethods()) {
            Tool tool = m.getAnnotation(Tool.class);
            // 描述面向 LLM 的工具选择，空/过短描述会劣化路由质量
            assertThat(tool.description()).as("工具 %s 描述", tool.name()).isNotBlank();
            assertThat(tool.description().length()).as("工具 %s 描述长度", tool.name()).isGreaterThanOrEqualTo(10);
        }
    }

    @Test
    void everyToolParamIsDocumented() {
        for (Method m : toolMethods()) {
            for (var param : m.getParameters()) {
                ToolParam p = param.getAnnotation(ToolParam.class);
                if (p != null) {
                    assertThat(p.description()).as("%s.%s 参数描述", m.getAnnotation(Tool.class).name(), param.getName())
                            .isNotBlank();
                }
            }
        }
    }
}

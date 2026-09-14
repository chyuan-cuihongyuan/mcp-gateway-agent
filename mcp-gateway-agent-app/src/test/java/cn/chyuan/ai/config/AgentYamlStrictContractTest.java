package cn.chyuan.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * agent 配置 yml 严格解析契约（SELFLOOP3 loop-309，工单 0416/0417）。
 *
 * 智能体装配配置在运行时加载，yaml 重复键会被静默覆盖（后键胜出）；
 * snakeyaml 2.x SafeConstructor 默认禁止重复键，本守卫让配置漂移在 CI 期即失败。
 */
class AgentYamlStrictContractTest {

    @Test
    void allAgentYmlsParseStrictlyWithoutDuplicateKeys() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:agent/*.yml");

        // 文件数下限：防止配置被误删后守卫静默通过（当前 7 个）
        assertThat(resources.length).isGreaterThanOrEqualTo(7);

        Yaml strictYaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        List<String> names = new ArrayList<>();
        for (Resource resource : resources) {
            try (var is = resource.getInputStream()) {
                // 重复键在此抛 DuplicateKeyException → 测试失败
                Object loaded = strictYaml.load(is);
                assertThat(loaded).as("%s 顶层应为映射", resource.getFilename()).isInstanceOf(Map.class);
                assertThat((Map<?, ?>) loaded).as("%s 顶层不应为空", resource.getFilename()).isNotEmpty();
                names.add(resource.getFilename());
            }
        }
        assertThat(names).contains("deepseek-agent.yml", "zhipu-agent.yml");
    }
}

package cn.chyuan.ai.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * b-01 模板契约：mcp.http.pool 两键必须在 example 模板中存在且可解析为数值
 * （承 al-27「yml 严格解析契约」模式；纯文件解析，不启 Spring 上下文）。
 */
class HttpClientPoolConfigContractTest {

    private static final Path TEMPLATE =
            Path.of("src/main/resources/application-test.yml.example");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> httpSection() throws IOException {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(Files.readString(TEMPLATE));
        assertThat(root).as("模板根节点").isNotNull();
        Map<String, Object> mcp = (Map<String, Object>) root.get("mcp");
        assertThat(mcp).as("mcp 段").isNotNull();
        Map<String, Object> http = (Map<String, Object>) mcp.get("http");
        assertThat(http).as("mcp.http 段").isNotNull();
        return http;
    }

    @Test
    void templateCarriesPoolTuningKeys() throws IOException {
        Map<String, Object> pool = (Map<String, Object>) httpSection().get("pool");
        assertThat(pool).as("mcp.http.pool 段（b-01 外置键）").isNotNull();
        assertThat(((Number) pool.get("max-idle-connections")).intValue()).isEqualTo(20);
        assertThat(((Number) pool.get("keep-alive-minutes")).intValue()).isEqualTo(5);
    }

    @Test
    void timeoutKeysStillPresentBesidePool() throws IOException {
        Map<String, Object> http = httpSection();
        assertThat(((Number) http.get("connect-timeout-ms")).intValue()).isPositive();
        assertThat(((Number) http.get("read-timeout-ms")).intValue()).isPositive();
        assertThat(((Number) http.get("write-timeout-ms")).intValue()).isPositive();
    }
}

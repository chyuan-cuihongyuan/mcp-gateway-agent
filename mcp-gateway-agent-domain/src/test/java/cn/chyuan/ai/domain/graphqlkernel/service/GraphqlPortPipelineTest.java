package cn.chyuan.ai.domain.graphqlkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GraphqlPort 组合管线测试（工单 0686 CC8，graphql-js 思想）。
 * schema+query+variables→result/errors 全链/错误请求级归一（data null）/
 * 工具清单映射 schema 字段形态（promptresource 只读联动）/非法入参拒绝。
 */
class GraphqlPortPipelineTest {

    static final String SDL = """
            type Query {
              tool_search(input: String): String
              tool_echo(input: String): String
            }
            """;

    @Test
    void portFullChainWithResolversAndErrors() {
        GraphqlPort port = new GraphqlPort.InMemoryGraphql();
        GraphqlSchema schema = port.compile(SDL);
        Map<String, Executor.FieldResolver> resolvers = Map.of(
                "Query.tool_search", (parent, args, vars) -> "hits:" + args.get("input"),
                "Query.tool_echo", (parent, args, vars) -> {
                    throw new IllegalStateException("echo 下线");
                });
        GraphqlPort.GqlResult result = port.execute(schema, resolvers,
                "query($q: String) { tool_search(input: $q) tool_echo(input: \"x\") }",
                Map.of("q", "kv"));
        assertFalse(result.success(), "echo 失败应有错误");
        assertEquals("hits:kv", result.data().get("tool_search"), "变量注入 resolver 参数");
        assertNull(result.data().get("tool_echo"));
        assertEquals("tool_echo", result.errors().get(0).path().get(0));
    }

    @Test
    void portRequestLevelErrorDataNull() {
        GraphqlPort port = new GraphqlPort.InMemoryGraphql();
        GraphqlSchema schema = port.compile(SDL);
        GraphqlPort.GqlResult bad = port.execute(schema, "{ tool_missing }", Map.of());
        assertNull(bad.data(), "请求级错误 data 为 null");
        assertTrue(bad.errors().get(0).message().contains("无字段 tool_missing"));
        GraphqlPort.GqlResult syntax = port.execute(schema, "{ tool_search ", Map.of());
        assertNull(syntax.data());
        assertTrue(syntax.errors().get(0).message().contains("截断"));
    }

    @Test
    void portSdlFromToolListPromptResourceShape() {
        GraphqlPort port = new GraphqlPort.InMemoryGraphql();
        String sdl = port.sdlFromToolList(List.of(
                Map.of("name", "tool_search", "description", "检索工具"),
                Map.of("name", "tool_echo", "description", "回声工具")));
        GraphqlSchema schema = port.compile(sdl + "type QueryRoot { x: String }");
        assertTrue(schema.type("Query").fields().containsKey("tool_search"), "工具映射为 schema 字段");
        assertTrue(schema.type("Query").fields().containsKey("tool_echo"));
        assertTrue(sdl.contains("# 检索工具"), "描述以注释保留");
        assertThrows(IllegalArgumentException.class,
                () -> port.sdlFromToolList(List.of(Map.of("name", "9bad"))));
        assertThrows(IllegalArgumentException.class, () -> port.sdlFromToolList(null));
        assertThrows(IllegalArgumentException.class, () -> port.execute(null, "{ x }", Map.of()));
    }
}

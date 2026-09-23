package cn.chyuan.ai.domain.graphqlkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GraphQL 端口+组合管线（工单 0686 CC8）。
 * schema+query+variables→result/errors 全链（parse→validate→execute）；
 * 与 promptresource 只读联动（工具清单映射为 Query schema 字段形态，
 * 泛型入参不 import promptresource，不改其任何类）/
 * graphql-kernel.enabled 默认关（开启才改变行为）。
 */
public interface GraphqlPort {

    record Error(String message, List<Object> path) {
    }

    record GqlResult(Map<String, Object> data, List<Error> errors) {

        public boolean success() {
            return errors.isEmpty();
        }
    }

    GraphqlSchema compile(String sdl);

    /** 全链执行：自定义 resolver 注入 */
    GqlResult execute(GraphqlSchema schema, Map<String, Executor.FieldResolver> resolvers,
                      String query, Map<String, Object> variables);

    /** 全链执行：默认 Map 取值（无自定义 resolver） */
    GqlResult execute(GraphqlSchema schema, String query, Map<String, Object> variables);

    /**
     * 与 promptresource 只读联动：工具清单（name/description）映射为
     * Query schema SDL 字段形态（泛型入参，不 import promptresource）。
     */
    String sdlFromToolList(List<Map<String, String>> tools);

    /** 内存假实现：GraphqlSchema+QueryParser+Validator+Executor 全链 */
    class InMemoryGraphql implements GraphqlPort {

        @Override
        public GraphqlSchema compile(String sdl) {
            return GraphqlSchema.parse(sdl);
        }

        @Override
        public GqlResult execute(GraphqlSchema schema, Map<String, Executor.FieldResolver> resolvers,
                                 String query, Map<String, Object> variables) {
            if (schema == null) {
                throw new IllegalArgumentException("schema 不得为 null");
            }
            try {
                QueryParser.Document document = QueryParser.parse(query);
                Validator.validate(schema, document);
                Executor.ExecutionResult result = new Executor(schema, resolvers).execute(document, variables);
                List<Error> errors = new ArrayList<>();
                for (Executor.GqlError error : result.errors()) {
                    errors.add(new Error(error.message(), error.path()));
                }
                return new GqlResult(result.data(), List.copyOf(errors));
            } catch (IllegalArgumentException e) {
                return new GqlResult(null, List.of(new Error(e.getMessage(), List.of())));
            }
        }

        @Override
        public GqlResult execute(GraphqlSchema schema, String query, Map<String, Object> variables) {
            return execute(schema, Map.of(), query, variables);
        }

        @Override
        public String sdlFromToolList(List<Map<String, String>> tools) {
            if (tools == null) {
                throw new IllegalArgumentException("工具清单不得为 null");
            }
            StringBuilder sb = new StringBuilder("type Query {\n");
            for (Map<String, String> tool : tools) {
                String name = tool.getOrDefault("name", "");
                if (name.isBlank() || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    throw new IllegalArgumentException("工具名非法：" + name);
                }
                sb.append("  ").append(name).append("(input: String): String")
                        .append("  # ").append(tool.getOrDefault("description", "")).append('\n');
            }
            return sb.append("}\n").toString();
        }
    }
}

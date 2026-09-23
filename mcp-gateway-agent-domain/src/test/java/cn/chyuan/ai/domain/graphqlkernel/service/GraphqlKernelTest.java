package cn.chyuan.ai.domain.graphqlkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GraphQL 内核域单测（工单 0679-0685 CC1-CC5·CC7，graphql-js 思想）。
 * SDL 解析与重名拒绝/查询解析别名参数 fragment/校验字段存在性叶子规则
 * 非空参数未定义变量/执行器链与列表源/变量 coercion 与 skip·include/
 * 错误规范化部分数据并存/最小内省。
 */
class GraphqlKernelTest {

    static final String SDL = """
            # 工具目录
            type Query {
              hero(episode: Int!): Character
              heroes: [Character]
              version: String
            }
            type Character {
              name: String
              friends: [Character]
              level: Int
            }
            enum Episode { NEWHOPE EMPIRE JEDI }
            input HeroInput {
              name: String
            }
            scalar Price
            """;

    @Test
    void sdlParseTypesFieldsAndTypeRefs() {
        GraphqlSchema schema = GraphqlSchema.parse(SDL);
        assertEquals(GraphqlSchema.Kind.OBJECT, schema.type("Query").kind());
        GraphqlSchema.FieldDef hero = schema.type("Query").fields().get("hero");
        assertTrue(hero.type() instanceof GraphqlSchema.Named, "hero 返回命名类型");
        assertTrue(hero.args().get(0).type() instanceof GraphqlSchema.NonNullT, "episode 参数非空");
        GraphqlSchema.FieldDef heroes = schema.type("Query").fields().get("heroes");
        GraphqlSchema.TypeRef inner = GraphqlSchema.unwrap(heroes.type());
        assertEquals("Character", ((GraphqlSchema.Named) inner).name(), "[Character] 列表内层");
        assertEquals(GraphqlSchema.Kind.ENUM, schema.type("Episode").kind());
        assertEquals(List.of("NEWHOPE", "EMPIRE", "JEDI"), schema.type("Episode").enumValues());
        assertEquals(GraphqlSchema.Kind.INPUT, schema.type("HeroInput").kind());
        assertEquals(GraphqlSchema.Kind.SCALAR, schema.type("Price").kind());
        assertEquals("[String]!", schema.typeQualifiedName(new GraphqlSchema.NonNullT(new GraphqlSchema.ListT(new GraphqlSchema.Named("String")))), "类型引用名序列化");
    }

    @Test
    void sdlRejectsDuplicateAndSyntaxErrors() {
        assertThrows(IllegalArgumentException.class, () -> GraphqlSchema.parse("""
                type Query {
                  a: String
                }
                type Query {
                  b: String
                }
                """), "重名类型拒绝");
        assertThrows(IllegalArgumentException.class, () -> GraphqlSchema.parse("type Query { a: String "), "缺 }");
        assertThrows(IllegalArgumentException.class, () -> GraphqlSchema.parse("type Query { a: String } extra"), "未知定义");
        assertThrows(IllegalArgumentException.class, () -> GraphqlSchema.parse(""), "空 SDL");
    }

    @Test
    void queryParseAliasArgsFragments() {
        QueryParser.Document doc = QueryParser.parse("""
                query Q($ep: Int! = 1) {
                  h: hero(episode: $ep) {
                    name
                    ...c
                  }
                }
                fragment c on Character {
                  level @include(if: true)
                }
                """);
        QueryParser.Operation op = doc.soleOperation();
        assertEquals("Q", op.name());
        assertEquals("ep", op.varDefs().get(0).name());
        assertEquals("Int!", op.varDefs().get(0).typeText());
        QueryParser.FieldSel hero = (QueryParser.FieldSel) op.selections().get(0);
        assertEquals("hero", hero.name());
        assertEquals("h", hero.alias());
        assertTrue(hero.args().get("episode") instanceof QueryParser.VarL);
        QueryParser.FragmentSpread spread = (QueryParser.FragmentSpread) hero.selections().get(1);
        assertEquals("c", spread.name());
        assertTrue(doc.fragments().containsKey("c"));
        assertTrue(doc.fragments().get("c").get(0) instanceof QueryParser.FieldSel);
    }

    @Test
    void queryParseLiteralsAndInlineFragment() {
        QueryParser.Document doc = QueryParser.parse("""
                {
                  hero(episode: 6, tags: ["a", 2], meta: {k: ACTIVE}, note: null, ok: false) {
                    ... on Character { name }
                  }
                }
                """);
        QueryParser.FieldSel hero = (QueryParser.FieldSel) doc.soleOperation().selections().get(0);
        assertEquals(6L, ((QueryParser.IntL) hero.args().get("episode")).v());
        assertEquals("a", ((QueryParser.StrL) ((QueryParser.ListL) hero.args().get("tags")).items().get(0)).s());
        assertEquals("ACTIVE", ((QueryParser.EnumL) ((QueryParser.ObjectL) hero.args().get("meta")).fields().get("k")).name());
        assertTrue(hero.args().get("note") instanceof QueryParser.NullL);
        assertTrue(hero.args().get("ok") instanceof QueryParser.BoolL);
        QueryParser.InlineFragment inline = (QueryParser.InlineFragment) hero.selections().get(0);
        assertEquals("Character", inline.onType());
        assertThrows(IllegalArgumentException.class, () -> QueryParser.parse("{ hero(: 1) }"), "参数名缺失");
        assertThrows(IllegalArgumentException.class, () -> QueryParser.parse("mutation { x }"), "仅支持 query");
    }

    @Test
    void validateFieldExistenceLeavesAndArgs() {
        GraphqlSchema schema = GraphqlSchema.parse(SDL);
        Validator.validate(schema, QueryParser.parse("{ version }"));
        Validator.validate(schema, QueryParser.parse("{ heroes { name friends { name } } }"));
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> Validator.validate(schema, QueryParser.parse("{ nope }")));
        assertTrue(missing.getMessage().contains("Query 无字段 nope"));
        IllegalArgumentException leaf = assertThrows(IllegalArgumentException.class,
                () -> Validator.validate(schema, QueryParser.parse("{ version { x } }")));
        assertTrue(leaf.getMessage().contains("叶字段"));
        IllegalArgumentException composite = assertThrows(IllegalArgumentException.class,
                () -> Validator.validate(schema, QueryParser.parse("{ heroes }")));
        assertTrue(composite.getMessage().contains("必须带子选择集"));
        IllegalArgumentException arg = assertThrows(IllegalArgumentException.class,
                () -> Validator.validate(schema, QueryParser.parse("{ hero { name } }")));
        assertTrue(arg.getMessage().contains("非空参数 episode"));
        IllegalArgumentException extraArg = assertThrows(IllegalArgumentException.class,
                () -> Validator.validate(schema, QueryParser.parse("{ hero(episode: 1, bogus: 2) { name } }")));
        assertTrue(extraArg.getMessage().contains("未知参数 bogus"));
    }

    @Test
    void validateVariablesAndFragments() {
        GraphqlSchema schema = GraphqlSchema.parse(SDL);
        IllegalArgumentException undeclared = assertThrows(IllegalArgumentException.class,
                () -> Validator.validate(schema, QueryParser.parse("query { hero(episode: $ep) { name } }")));
        assertTrue(undeclared.getMessage().contains("未定义变量 $ep"));
        IllegalArgumentException noFragment = assertThrows(IllegalArgumentException.class,
                () -> Validator.validate(schema, QueryParser.parse("{ ...f }")));
        assertTrue(noFragment.getMessage().contains("未定义 fragment"));
        Validator.validate(schema, QueryParser.parse("query($ep: Int!) { hero(episode: $ep) { name } }"));
    }

    @Test
    void executeResolversListsAndTypename() {
        GraphqlSchema schema = GraphqlSchema.parse(SDL);
        Map<String, List<Map<String, Object>>> friends = Map.of(
                "luke", List.of(Map.of("name", "han", "level", 9), Map.of("name", "leia", "level", 7)));
        Map<String, Executor.FieldResolver> resolvers = Map.of(
                "Query.hero", (parent, args, vars) -> Map.of(
                        "name", "luke",
                        "level", 5,
                        "friends", friends.get("luke")),
                "Character.friends", (parent, args, vars) -> ((Map<?, ?>) parent).get("friends"));
        Executor executor = new Executor(schema, resolvers);
        Executor.ExecutionResult result = executor.execute(QueryParser.parse("""
                {
                  hero(episode: 1) {
                    __typename
                    name
                    friends { name }
                  }
                }
                """), Map.of());
        assertFalse(result.hasErrors());
        Map<String, Object> hero = (Map<String, Object>) result.data().get("hero");
        assertEquals("Character", hero.get("__typename"));
        assertEquals("luke", hero.get("name"));
        List<Map<String, Object>> list = (List<Map<String, Object>>) hero.get("friends");
        assertEquals(2, list.size());
        assertEquals("han", list.get(0).get("name"));
    }

    @Test
    void executeVariableCoercionAndDefaults() {
        GraphqlSchema schema = GraphqlSchema.parse(SDL);
        Map<String, Object> captured = new java.util.LinkedHashMap<>();
        Executor executor = new Executor(schema, Map.of("Query.hero", (parent, args, vars) -> {
            captured.putAll(args);
            return Map.of("name", "x");
        }));
        executor.execute(QueryParser.parse("query($ep: Int! = 3) { hero(episode: $ep) { name } }"), Map.of());
        assertEquals(3L, captured.get("episode"), "缺省用变量默认值");
        executor.execute(QueryParser.parse("query($ep: Int!) { hero(episode: $ep) { name } }"), Map.of("ep", 7));
        assertEquals(7L, captured.get("episode"));
        assertThrows(IllegalArgumentException.class,
                () -> executor.execute(QueryParser.parse("query($ep: Int!) { hero(episode: $ep) { name } }"), Map.of()),
                "非空变量缺失拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> executor.execute(QueryParser.parse("query($ep: String!) { hero(episode: $ep) { name } }"), Map.of("ep", 7)),
                "变量类型不符拒绝");
    }

    @Test
    void executeSkipIncludeDirectives() {
        GraphqlSchema schema = GraphqlSchema.parse(SDL);
        Executor executor = new Executor(schema, Map.of());
        Executor.ExecutionResult result = executor.execute(QueryParser.parse("""
                query($hide: Boolean!) {
                  version @skip(if: $hide)
                  hero(episode: 1) @include(if: true) { name }
                }
                """), Map.of("hide", true));
        assertFalse(result.data().containsKey("version"), "skip(true) 隐藏字段");
        assertTrue(result.data().containsKey("hero"), "include(true) 保留字段");
    }

    @Test
    void executeErrorNormalizationPartialData() {
        GraphqlSchema schema = GraphqlSchema.parse(SDL);
        Executor executor = new Executor(schema, Map.of(
                "Query.version", (parent, args, vars) -> {
                    throw new IllegalStateException("版本服务不可用");
                },
                "Query.hero", (parent, args, vars) -> Map.of("name", "luke")));
        Executor.ExecutionResult result = executor.execute(QueryParser.parse("{ version hero { name } }"), Map.of());
        assertTrue(result.hasErrors());
        assertNull(result.data().get("version"), "失败字段置 null");
        assertEquals("luke", ((Map<String, Object>) result.data().get("hero")).get("name"), "兄弟字段不中断");
        assertEquals(1, result.errors().size());
        assertEquals(List.of("version"), result.errors().get(0).path());
    }

    @Test
    void introspectionMinimalCatalog() {
        GraphqlSchema schema = GraphqlSchema.parse(SDL);
        Executor executor = new Executor(schema, Map.of());
        Executor.ExecutionResult schemaResult = executor.execute(QueryParser.parse("""
                { __schema { queryType { name } types { kind name } } }
                """), Map.of());
        assertFalse(schemaResult.hasErrors());
        Map<String, Object> intro = (Map<String, Object>) schemaResult.data().get("__schema");
        assertEquals("Query", ((Map<String, Object>) intro.get("queryType")).get("name"));
        List<Map<String, Object>> types = (List<Map<String, Object>>) intro.get("types");
        assertTrue(types.stream().anyMatch(t -> "Character".equals(t.get("name"))));
    }

    @Test
    void introspectionByName() {
        GraphqlSchema schema = GraphqlSchema.parse(SDL);
        Executor executor = new Executor(schema, Map.of());
        Executor.ExecutionResult result = executor.execute(QueryParser.parse(
                "{ __type(name: \"Character\") { name kind fields { name } } }"), Map.of());
        Map<String, Object> type = (Map<String, Object>) result.data().get("__type");
        assertEquals("Character", type.get("name"));
        List<Map<String, Object>> fields = (List<Map<String, Object>>) type.get("fields");
        assertTrue(fields.stream().anyMatch(f -> "friends".equals(f.get("name"))));
    }
}

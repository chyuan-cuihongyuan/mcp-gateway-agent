package cn.chyuan.ai.types.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * 请求标签解析与清洗测试（工单 0088）
 */
@DisplayName("请求标签解析测试")
class TagParserTest {

    @Test
    @DisplayName("头解析：中英文逗号分隔 + trim + 去空")
    void parseHeader() {
        Assertions.assertEquals(List.of(), TagParser.parseHeader(null));
        Assertions.assertEquals(List.of(), TagParser.parseHeader("  "));
        Assertions.assertEquals(List.of("proj-a", "team-1"), TagParser.parseHeader(" proj-a ， team-1 "));
        Assertions.assertEquals(List.of("a"), TagParser.parseHeader("a,,,"));
    }

    @Test
    @DisplayName("清洗：超长丢弃、去重保序、最多 5 个")
    void cleanRules() {
        String longTag = "x".repeat(33);
        Assertions.assertEquals(List.of("a", "b"),
                TagParser.clean(java.util.Arrays.asList("a", "b", "a", longTag, "")));
        Assertions.assertEquals(5, TagParser.clean(List.of("1", "2", "3", "4", "5", "6", "7")).size());
        Assertions.assertEquals(List.of(), TagParser.clean(null));
    }

    @Test
    @DisplayName("合并：body 来源优先靠前 + 重清洗")
    void mergeSources() {
        List<String> merged = TagParser.merge(List.of("h1", "h2"), List.of("b1", "h1"));
        Assertions.assertEquals(List.of("b1", "h1", "h2"), merged);
    }

    @Test
    @DisplayName("落库/展示形互转：规范形 ,a,b, 支撑精确 LIKE")
    void storageForms() {
        Assertions.assertEquals(",a,b,", TagParser.toStorage(List.of("a", "b")));
        Assertions.assertNull(TagParser.toStorage(null));
        Assertions.assertNull(TagParser.toStorage(List.of()));
        Assertions.assertEquals("a,b", TagParser.toDisplay(",a,b,"));
        Assertions.assertNull(TagParser.toDisplay(null));
        Assertions.assertNull(TagParser.toDisplay(","));
    }
}

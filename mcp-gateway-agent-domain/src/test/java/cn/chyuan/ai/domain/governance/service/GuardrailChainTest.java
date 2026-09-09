package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.adapter.repository.IGuardrailRepository;
import cn.chyuan.ai.domain.governance.model.valobj.GuardrailVO;
import cn.chyuan.ai.types.exception.AppException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

/**
 * 治理护栏执行链测试（工单 0091：顺序/短路/流量面过滤/规则求值/异常容错）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("治理护栏执行链测试")
class GuardrailChainTest {

    @Mock
    private IGuardrailRepository repository;

    @InjectMocks
    private GuardrailChain chain;

    @BeforeEach
    void init() {
        chain.init();
    }

    private GuardrailVO rule(String name, String type, String mode, String traffic, String config) {
        return GuardrailVO.builder()
                .id(1L).name(name).type(type).mode(mode).trafficMask(traffic)
                .config(config).priority(10).enabled(1).build();
    }

    @Test
    @DisplayName("无规则直通（零规则零改写）；空文本直通")
    void passthroughWhenNoRules() {
        Mockito.when(repository.findAll()).thenReturn(List.of());
        Assertions.assertFalse(chain.evaluate("LLM", GuardrailVO.MODE_PRE_CALL, "any text").blocked());
        Assertions.assertFalse(chain.evaluate("MCP", GuardrailVO.MODE_PRE_CALL, null).blocked());
    }

    @Test
    @DisplayName("流量面与模式过滤：LLM-only 规则不作用于 MCP；PRE 不作用于 POST")
    void trafficAndModeFilter() {
        Mockito.when(repository.findAll()).thenReturn(List.of(
                rule("llm-only", GuardrailVO.TYPE_KEYWORD_BLOCK, GuardrailVO.MODE_PRE_CALL, "LLM",
                        "{\"keywords\":[\"secret\"]}")));
        Assertions.assertTrue(chain.evaluate("LLM", GuardrailVO.MODE_PRE_CALL, "has secret inside").blocked());
        Assertions.assertFalse(chain.evaluate("MCP", GuardrailVO.MODE_PRE_CALL, "has secret inside").blocked());
        Assertions.assertFalse(chain.evaluate("LLM", GuardrailVO.MODE_POST_CALL, "has secret inside").blocked());
    }

    @Test
    @DisplayName("BLOCK 短路并报命中规则名；assertAllowed 抛 -32018")
    void blockShortCircuit() {
        Mockito.when(repository.findAll()).thenReturn(List.of(
                rule("kw", GuardrailVO.TYPE_KEYWORD_BLOCK, GuardrailVO.MODE_PRE_CALL, "ALL",
                        "{\"keywords\":[\"forbidden\"]}")));
        GuardrailChain.GuardrailOutcome outcome =
                chain.evaluate("MCP", GuardrailVO.MODE_PRE_CALL, "xx forbidden yy");
        Assertions.assertTrue(outcome.blocked());
        Assertions.assertEquals("kw", outcome.hitRule());

        AppException ex = Assertions.assertThrows(AppException.class,
                () -> chain.assertAllowed("MCP", GuardrailVO.MODE_PRE_CALL, "xx forbidden yy"));
        Assertions.assertEquals("-32018", ex.getCode());
    }

    @Test
    @DisplayName("MASK 改写继续链：PII 四类脱敏 + 改写结果可供后续规则")
    void maskRewrite() {
        Mockito.when(repository.findAll()).thenReturn(List.of(
                rule("pii", GuardrailVO.TYPE_PII_MASK, GuardrailVO.MODE_PRE_CALL, "ALL", "{}")));
        String text = "联系 13812345678 或 a@b.com，身份证 11010119900307777X";
        GuardrailChain.GuardrailOutcome outcome =
                chain.evaluate("LLM", GuardrailVO.MODE_PRE_CALL, text);
        Assertions.assertFalse(outcome.blocked());
        Assertions.assertTrue(outcome.masked(), "应发生脱敏：" + outcome.text());
        Assertions.assertTrue(outcome.text().contains("[PII:phone]"), "phone 未脱敏：" + outcome.text());
        Assertions.assertTrue(outcome.text().contains("[PII:email]"));
        Assertions.assertTrue(outcome.text().contains("[PII:idcard]"));
        Assertions.assertFalse(outcome.text().contains("13812345678"));
    }

    @Test
    @DisplayName("REGEX_BLOCK 正则命中；PII 单类开关关闭则不脱敏该类")
    void regexAndPiiToggles() {
        Mockito.when(repository.findAll()).thenReturn(List.of(
                rule("re", GuardrailVO.TYPE_REGEX_BLOCK, GuardrailVO.MODE_PRE_CALL, "ALL",
                        "{\"patterns\":[\"(?i)drop\\\\s+table\"]}")));
        Assertions.assertTrue(chain.evaluate("LLM", GuardrailVO.MODE_PRE_CALL, "please DROP TABLE users").blocked());
        Assertions.assertFalse(chain.evaluate("LLM", GuardrailVO.MODE_PRE_CALL, "select 1").blocked());

        Mockito.when(repository.findAll()).thenReturn(List.of(
                rule("pii", GuardrailVO.TYPE_PII_MASK, GuardrailVO.MODE_PRE_CALL, "ALL",
                        "{\"phone\":true,\"email\":false}")));
        GuardrailChain.GuardrailOutcome outcome = chain.evaluate("LLM", GuardrailVO.MODE_PRE_CALL,
                "13812345678 a@b.com");
        Assertions.assertTrue(outcome.text().contains("[PII:phone]"));
        Assertions.assertTrue(outcome.text().contains("a@b.com"), "email 关闭不脱敏");
    }

    @Test
    @DisplayName("非法配置跳过不 fail 流量；explainHits 输出命中规则名")
    void invalidConfigTolerated() {
        Mockito.when(repository.findAll()).thenReturn(List.of(
                rule("bad-json", GuardrailVO.TYPE_KEYWORD_BLOCK, GuardrailVO.MODE_PRE_CALL, "ALL", "{not-json")));
        Assertions.assertFalse(chain.evaluate("LLM", GuardrailVO.MODE_PRE_CALL, "anything").blocked());

        Mockito.when(repository.findAll()).thenReturn(List.of(
                rule("kw", GuardrailVO.TYPE_KEYWORD_BLOCK, GuardrailVO.MODE_PRE_CALL, "ALL",
                        "{\"keywords\":[\"secret\"]}")));
        Assertions.assertEquals(List.of("kw"),
                chain.explainHits("LLM", GuardrailVO.MODE_PRE_CALL, "a secret"));
    }

    @Test
    @DisplayName("遮蔽策略（0092）：keepPrefix/keepSuffix 保留首尾明文；过短整体 ***")
    void maskKeepStrategy() {
        Mockito.when(repository.findAll()).thenReturn(List.of(
                rule("pii-keep", GuardrailVO.TYPE_PII_MASK, GuardrailVO.MODE_PRE_CALL, "ALL",
                        "{\"keepPrefix\":3,\"keepSuffix\":2}")));
        GuardrailChain.GuardrailOutcome outcome = chain.evaluate("LLM", GuardrailVO.MODE_PRE_CALL,
                "13812345678");
        Assertions.assertTrue(outcome.masked());
        Assertions.assertEquals("138***78", outcome.text());

        Mockito.when(repository.findAll()).thenReturn(List.of(
                rule("pii-keep", GuardrailVO.TYPE_PII_MASK, GuardrailVO.MODE_PRE_CALL, "ALL",
                        "{\"keepPrefix\":8,\"keepSuffix\":8}")));
        Assertions.assertEquals("***", chain.evaluate("LLM", GuardrailVO.MODE_PRE_CALL, "13812345678").text());
    }

    @Test
    @DisplayName("响应侧类型（0094）：RESPONSE_MASK/RESPONSE_FILTER 在 POST_CALL 求值；LOGGING_ONLY 不拦截不改写")
    void responseModeAndLoggingOnly() {
        Mockito.when(repository.findAll()).thenReturn(List.of(
                rule("resp-mask", GuardrailVO.TYPE_RESPONSE_MASK, GuardrailVO.MODE_POST_CALL, "ALL", "{}"),
                rule("resp-filter", GuardrailVO.TYPE_RESPONSE_FILTER, GuardrailVO.MODE_POST_CALL, "ALL",
                        "{\"keywords\":[\"malicious\"]}")));
        GuardrailChain.GuardrailOutcome masked =
                chain.evaluate("LLM", GuardrailVO.MODE_POST_CALL, "邮箱 a@b.com");
        Assertions.assertTrue(masked.masked());
        Assertions.assertTrue(masked.text().contains("[PII:email]"));

        GuardrailChain.GuardrailOutcome blocked =
                chain.evaluate("MCP", GuardrailVO.MODE_POST_CALL, "contains malicious payload");
        Assertions.assertTrue(blocked.blocked());
        Assertions.assertEquals("resp-filter", blocked.hitRule());

        // LOGGING_ONLY：链过滤后无规则作用（当前无原始载荷落库——隐私面不存原文，语义保留为接口位）
        Mockito.when(repository.findAll()).thenReturn(List.of(
                rule("log-only", GuardrailVO.TYPE_KEYWORD_BLOCK, GuardrailVO.MODE_LOGGING_ONLY, "ALL",
                        "{\"keywords\":[\"secret\"]}")));
        Assertions.assertFalse(chain.evaluate("LLM", GuardrailVO.MODE_PRE_CALL, "a secret").blocked());
        Assertions.assertFalse(chain.evaluate("LLM", GuardrailVO.MODE_PRE_CALL, "a secret").masked());
    }
}
